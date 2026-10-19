package com.paymentgateway.api.web;

import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paymentgateway.api.checkout.CheckoutSessionService;
import com.paymentgateway.common.Hashing;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.contract.payment.CreatePaymentCommand;
import com.paymentgateway.contract.payment.CreatePaymentResult;
import com.paymentgateway.contract.payment.DuplicateMerchantOrderException;
import com.paymentgateway.contract.payment.PaymentRpcService;
import com.paymentgateway.contract.payment.RefundPaymentCommand;
import com.paymentgateway.contract.payment.VoidPaymentCommand;

import jakarta.validation.Valid;

/**
 * Exposes HTTP endpoints for merchant payment operations.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class MerchantPaymentController {
	@DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
	private PaymentRpcService paymentRpcService;
	private final CheckoutSessionService checkoutSessions;

	/**
	 * Creates a new {@code MerchantPaymentController} instance.
	 *
	 * @param checkoutSessions the checkout sessions value
	 */
	public MerchantPaymentController(CheckoutSessionService checkoutSessions) {
		this.checkoutSessions = checkoutSessions;
	}

	/**
	 * Executes the create operation.
	 *
	 * @param merchantId     the merchant identifier
	 * @param idempotencyKey the idempotency key used to deduplicate retries
	 * @param request        the current request or request payload
	 * @return the resulting {@code ResponseEntity<CreatePaymentResponse>} value
	 */
	@PostMapping
	public ResponseEntity<CreatePaymentResponse> create(@RequestHeader("X-Merchant-Id") UUID merchantId,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CreatePaymentRequest request) {
		validateKey(idempotencyKey);
		String currency = request.currency().toUpperCase(Locale.ROOT);
		String provider = ProviderCode.normalize(request.providerCode());
		Map<String, String> options = sanitizeOptions(request.providerOptions());
		String canonical = merchantId + "|" + request.merchantOrderId() + "|" + request.amountMinor() + "|" + currency
				+ "|" + request.description() + "|" + provider + "|" + new TreeMap<>(options);
		CreatePaymentResult result;
		try {
			result = paymentRpcService.createPayment(
					new CreatePaymentCommand(merchantId, request.merchantOrderId(), request.amountMinor(), currency,
							request.description(), provider, options, idempotencyKey, Hashing.sha256(canonical)));
		} catch (RuntimeException e) {
			// Dubbo can wrap a provider-side RuntimeException. Normalize the known business
			// conflict here so
			// the external HTTP API remains stable regardless of the RPC wrapper used by a
			// specific build.
			if (isDuplicateMerchantOrder(e))
				throw new DuplicateMerchantOrderException(request.merchantOrderId(), e);
			throw e;
		}

		String presentation = request.presentationMode() == null || request.presentationMode().isBlank() ? "QR_STRING"
				: request.presentationMode().toUpperCase(Locale.ROOT);
		String checkoutUrl = null;
		Instant expiresAt;
		if ("HOSTED_PAGE".equals(presentation)) {
			// Session validation/creation happens before payment-core is configured;
			// create() compensates the
			// local session insert if the authoritative payment-core update fails.
			var session = checkoutSessions.create(merchantId, result.paymentId(), request.returnUrl());
			checkoutUrl = session.checkoutUrl();
			expiresAt = session.expiresAt();
		} else {
			expiresAt = checkoutSessions.applyPaymentExpiry(merchantId, result.paymentId(), presentation);
		}
		return ResponseEntity.ok(new CreatePaymentResponse(result.paymentId(), result.status(), result.qr(),
				result.providerCode(), result.nextAction(), result.redirectUrl(), result.formFields(),
				"/api/v1/public/payments/" + result.paymentId() + "/status",
				"/api/v1/public/payments/" + result.paymentId() + "/events", checkoutUrl, expiresAt, result.message()));
	}

	/**
	 * Executes the get operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param paymentId  the payment identifier
	 * @return the resulting {@code Object} value
	 */
	@GetMapping("/{paymentId}")
	public Object get(@RequestHeader("X-Merchant-Id") UUID merchantId, @PathVariable UUID paymentId) {
		return paymentRpcService.getPayment(merchantId, paymentId);
	}

	/**
	 * Executes the reconcile operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param paymentId  the payment identifier
	 * @return the resulting {@code Object} value
	 */
	@PostMapping("/{paymentId}/reconcile")
	public Object reconcile(@RequestHeader("X-Merchant-Id") UUID merchantId, @PathVariable UUID paymentId) {
		return paymentRpcService.reconcilePayment(merchantId, paymentId);
	}

	/**
	 * Executes the refund operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param key        the lookup or signing key
	 * @param paymentId  the payment identifier
	 * @param request    the current request or request payload
	 * @return the resulting {@code Object} value
	 */
	@PostMapping("/{paymentId}/refunds")
	public Object refund(@RequestHeader("X-Merchant-Id") UUID merchantId, @RequestHeader("Idempotency-Key") String key,
			@PathVariable UUID paymentId, @Valid @RequestBody RefundPaymentRequest request) {
		validateKey(key);
		Map<String, String> options = sanitizeOptions(request.providerOptions());
		String canonical = merchantId + "|REFUND|" + paymentId + "|" + request.merchantRefundId() + "|"
				+ request.amountMinor() + "|" + new TreeMap<>(options);
		return paymentRpcService.refundPayment(new RefundPaymentCommand(merchantId, paymentId,
				request.merchantRefundId(), request.amountMinor(), options, key, Hashing.sha256(canonical)));
	}

	/**
	 * Voids payment.
	 *
	 * @param merchantId the merchant identifier
	 * @param key        the lookup or signing key
	 * @param paymentId  the payment identifier
	 * @param request    the current request or request payload
	 * @return the resulting {@code Object} value
	 */
	@PostMapping("/{paymentId}/void")
	public Object voidPayment(@RequestHeader("X-Merchant-Id") UUID merchantId,
			@RequestHeader("Idempotency-Key") String key, @PathVariable UUID paymentId,
			@RequestBody(required = false) VoidPaymentRequest request) {
		validateKey(key);
		Map<String, String> options = sanitizeOptions(request == null ? null : request.providerOptions());
		String canonical = merchantId + "|VOID|" + paymentId + "|" + new TreeMap<>(options);
		return paymentRpcService
				.voidPayment(new VoidPaymentCommand(merchantId, paymentId, options, key, Hashing.sha256(canonical)));
	}

	/**
	 * Determines whether duplicate merchant order.
	 *
	 * @param error the failure that triggered error handling
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean isDuplicateMerchantOrder(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof DuplicateMerchantOrderException)
				return true;
			String message = current.getMessage();
			if (message != null && (message.contains("DUPLICATE_MERCHANT_ORDER")
					|| message.contains("DuplicateMerchantOrderException")
					|| message.contains("uq_payment_merchant_order")
					|| (message.contains("merchantOrderId") && message.contains("already exists for this merchant"))))
				return true;
		}
		return false;
	}

	/**
	 * Validates key.
	 *
	 * @param key the lookup or signing key
	 */
	static void validateKey(String key) {
		if (key == null || key.isBlank() || key.length() > 128)
			throw new IllegalArgumentException("Idempotency-Key must be 1..128 characters");
	}

	/**
	 * Executes the sanitize options operation.
	 *
	 * @param input the input value
	 * @return the resulting key-value data
	 */
	static Map<String, String> sanitizeOptions(Map<String, String> input) {
		if (input == null || input.isEmpty())
			return Collections.emptyMap();
		if (input.size() > 32)
			throw new IllegalArgumentException("providerOptions supports at most 32 entries");
		var out = new TreeMap<String, String>();
		input.forEach((k, v) -> {
			if (k == null || k.isBlank() || k.length() > 64)
				throw new IllegalArgumentException("Invalid providerOptions key");
			if (v != null && v.length() > 2000)
				throw new IllegalArgumentException("providerOptions value too long for " + k);
			out.put(k, v == null ? "" : v);
		});
		return Collections.unmodifiableMap(out);
	}
}
