package com.paymentgateway.api.web;

import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paymentgateway.common.Hashing;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.contract.payment.CustomerQrInspectCommand;
import com.paymentgateway.contract.payment.CustomerQrPayCommand;
import com.paymentgateway.contract.payment.PaymentRpcService;

import jakarta.validation.Valid;

/**
 * Exposes HTTP endpoints for customer QR operations.
 */
@RestController
@RequestMapping("/api/v1/customer-qr")
public class CustomerQrController {
	@DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
	private PaymentRpcService paymentRpcService;

	/**
	 * Executes the inspect operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param request    the current request or request payload
	 * @return the resulting {@code Object} value
	 */
	@PostMapping("/inspect")
	public Object inspect(@RequestHeader("X-Merchant-Id") UUID merchantId,
			@Valid @RequestBody CustomerQrInspectRequest request) {
		return paymentRpcService.inspectCustomerQr(new CustomerQrInspectCommand(merchantId,
				ProviderCode.normalize(request.providerCode()), request.qrData(), request.deviceId(), request.shopId(),
				MerchantPaymentController.sanitizeOptions(request.providerOptions())));
	}

	/**
	 * Executes the pay operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param key        the lookup or signing key
	 * @param request    the current request or request payload
	 * @return the resulting {@code ResponseEntity<CreatePaymentResponse>} value
	 */
	@PostMapping("/pay")
	public ResponseEntity<CreatePaymentResponse> pay(@RequestHeader("X-Merchant-Id") UUID merchantId,
			@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CustomerQrPayRequest request) {
		MerchantPaymentController.validateKey(key);
		String provider = ProviderCode.normalize(request.providerCode());
		String currency = request.currency().toUpperCase(Locale.ROOT);
		var options = MerchantPaymentController.sanitizeOptions(request.providerOptions());
		String canonical = merchantId + "|CUSTOMER_QR_PAY|" + request.merchantOrderId() + "|" + request.amountMinor()
				+ "|" + currency + "|" + provider + "|" + request.customerPhone() + "|" + new TreeMap<>(options);
		var r = paymentRpcService.payCustomerQr(new CustomerQrPayCommand(merchantId, request.merchantOrderId(),
				request.amountMinor(), currency, request.description(), provider, request.customerPhone(), options, key,
				Hashing.sha256(canonical)));
		return ResponseEntity.ok(new CreatePaymentResponse(r.paymentId(), r.status(), r.qr(), r.providerCode(),
				r.nextAction(), r.redirectUrl(), r.formFields(), "/api/v1/public/payments/" + r.paymentId() + "/status",
				"/api/v1/public/payments/" + r.paymentId() + "/events", null, null, r.message()));
	}
}
