package com.paymentgateway.core.service;

import com.paymentgateway.common.Hashing;
import com.paymentgateway.common.PaymentStatus;
import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.contract.payment.CallbackAcceptance;
import com.paymentgateway.contract.error.InvalidPaymentStateException;
import com.paymentgateway.contract.error.ProviderOperationRejectedException;
import com.paymentgateway.contract.payment.CreatePaymentCommand;
import com.paymentgateway.contract.payment.CreatePaymentResult;
import com.paymentgateway.contract.payment.CustomerQrInspectCommand;
import com.paymentgateway.contract.payment.CustomerQrInspectResult;
import com.paymentgateway.contract.payment.CustomerQrPayCommand;
import com.paymentgateway.contract.payment.PaymentRpcService;
import com.paymentgateway.contract.payment.PaymentView;
import com.paymentgateway.contract.payment.ProviderCallbackCommand;
import com.paymentgateway.contract.payment.ProviderSummary;
import com.paymentgateway.contract.payment.RefundPaymentCommand;
import com.paymentgateway.contract.payment.RefundPaymentResult;
import com.paymentgateway.contract.payment.VoidPaymentCommand;
import com.paymentgateway.contract.provider.CustomerPresentQrProviderRpcService;
import com.paymentgateway.contract.provider.EnquirePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.ProviderCustomerQrInspectCommand;
import com.paymentgateway.contract.provider.ProviderCustomerQrPaymentCommand;
import com.paymentgateway.contract.provider.ProviderRefundCommand;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.contract.provider.ProviderVoidCommand;
import com.paymentgateway.contract.provider.RefundProviderRpcService;
import com.paymentgateway.contract.provider.VoidProviderRpcService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * Implements the payment RPC service contract.
 */
@DubboService(version = "1.0.0", group = "payment", timeout = 10000)
public class PaymentRpcServiceImpl implements PaymentRpcService {
	private final PaymentPersistenceService persistence;
	private final ProviderClientRegistry providers;

	/**
	 * Creates a new {@code PaymentRpcServiceImpl} instance.
	 *
	 * @param persistence the persistence value
	 * @param providers   the providers value
	 */
	public PaymentRpcServiceImpl(PaymentPersistenceService persistence, ProviderClientRegistry providers) {
		this.persistence = persistence;
		this.providers = providers;
	}

	/**
	 * Creates payment.
	 *
	 * @param c the create payment command
	 * @return the resulting {@code CreatePaymentResult} value
	 */
	@Override
	public CreatePaymentResult createPayment(CreatePaymentCommand c) {
		var reservation = persistence.reserveAndCreate(c);
		var current = persistence.require(reservation.paymentId());
		if (!reservation.created())
			return toCreateResult(current, "Idempotent replay; provider initiation not repeated");
		ProviderRpcService provider = providers.requireBase(current.getSelectedProviderCode());
		var result = provider.initiate(new InitiatePaymentCommand(reservation.paymentId(), c.merchantOrderId(),
				current.getProviderOrderId(), c.amountMinor(), c.currency(), c.description(), c.providerOptions()));
		persistence.persistInitiateOutcome(reservation.paymentId(), result);
		return toCreateResult(persistence.require(reservation.paymentId()), result.responseMessage());
	}

	/**
	 * Retrieves payment.
	 *
	 * @param merchantId the merchant identifier
	 * @param id         the resource identifier
	 * @return the resulting {@code PaymentView} value
	 */
	@Override
	public PaymentView getPayment(UUID merchantId, UUID id) {
		return view(persistence.requireOwned(id, merchantId));
	}

	/**
	 * Reconciles payment.
	 *
	 * @param merchantId the merchant identifier
	 * @param id         the resource identifier
	 * @return the resulting {@code PaymentView} value
	 */
	@Override
	public PaymentView reconcilePayment(UUID merchantId, UUID id) {
		var p = persistence.requireOwned(id, merchantId);
		if (p.getProviderOrderId() == null)
			throw new InvalidPaymentStateException("Payment has no provider order id");
		var provider = providers.require(p.getSelectedProviderCode(), ProviderCapability.ENQUIRY,
				ProviderRpcService.class);
		var r = provider.enquire(new EnquirePaymentCommand(id, p.getProviderOrderId(), p.getProviderReferenceId(),
				p.getProviderOrderDate() == null ? null : p.getProviderOrderDate().toString(),
				p.getProviderNextAction()));
		return view(persistence.applyVerifiedEnquiry(p, r));
	}

	/**
	 * Configures checkout.
	 *
	 * @param merchantId          the merchant identifier
	 * @param id                  the resource identifier
	 * @param presentationMode    the presentation mode value
	 * @param expiresAtEpochMilli the expires at epoch milli value
	 * @return the resulting {@code PaymentView} value
	 */
	@Override
	public PaymentView configureCheckout(UUID merchantId, UUID id, String presentationMode, long expiresAtEpochMilli) {
		return view(persistence.configureCheckout(merchantId, id, presentationMode,
				java.time.Instant.ofEpochMilli(expiresAtEpochMilli)));
	}

	/**
	 * Expires payment.
	 *
	 * @param merchantId the merchant identifier
	 * @param id         the resource identifier
	 * @return the resulting {@code PaymentView} value
	 */
	@Override
	public PaymentView expirePayment(UUID merchantId, UUID id) {
		return view(persistence.expirePayment(merchantId, id));
	}

	/**
	 * Processes the refund for payment.
	 *
	 * @param c the refund payment command
	 * @return the resulting {@code RefundPaymentResult} value
	 */
	@Override
	public RefundPaymentResult refundPayment(RefundPaymentCommand c) {
		var reservation = persistence.reserveRefund(c);
		var refund = persistence.requireRefund(reservation.refundId());
		if (!reservation.created())
			return refundResult(refund, "Idempotent refund replay; provider refund not repeated");
		var payment = persistence.requireOwned(c.paymentId(), c.merchantId());
		var provider = providers.require(payment.getSelectedProviderCode(), ProviderCapability.REFUND,
				RefundProviderRpcService.class);
		var result = provider.refund(new ProviderRefundCommand(payment.getPaymentId(), refund.getRefundId(),
				payment.getProviderOrderId(), payment.getProviderReferenceId(), refund.getMerchantRefundId(),
				refund.getAmountMinor(), payment.getAmountMinor(), payment.getCurrency(), c.providerOptions()));
		return refundResult(persistence.persistRefundOutcome(refund.getRefundId(), result), result.responseMessage());
	}

	/**
	 * Voids payment.
	 *
	 * @param c the void payment command
	 * @return the resulting {@code PaymentView} value
	 */
	@Override
	public PaymentView voidPayment(VoidPaymentCommand c) {
		var payment = persistence.requireOwned(c.paymentId(), c.merchantId());
		if (payment.getStatus() == PaymentStatus.REFUNDED || payment.getStatus() == PaymentStatus.VOIDED)
			throw new InvalidPaymentStateException("Refunded or voided payment cannot be voided again");
		if (!persistence.reservePaymentOperation(c.merchantId(), c.paymentId(), "VOID", c.idempotencyKey(),
				c.requestHash()))
			return view(payment);
		var provider = providers.require(payment.getSelectedProviderCode(), ProviderCapability.VOID,
				VoidProviderRpcService.class);
		var result = provider.voidPayment(new ProviderVoidCommand(payment.getPaymentId(), payment.getProviderOrderId(),
				payment.getProviderReferenceId(), payment.getAmountMinor(), payment.getCurrency(),
				c.providerOptions()));
		var updated = persistence.persistVoidOutcome(payment.getPaymentId(), result);
		if (result.outcome() != com.paymentgateway.common.ProviderOutcome.UNKNOWN)
			persistence.completePaymentOperation(c.merchantId(), c.paymentId(), "VOID", c.idempotencyKey());
		return view(updated);
	}

	/**
	 * Executes the inspect customer QR operation.
	 *
	 * @param c the customer QR inspect command
	 * @return the resulting {@code CustomerQrInspectResult} value
	 */
	@Override
	public CustomerQrInspectResult inspectCustomerQr(CustomerQrInspectCommand c) {
		String code = ProviderCode.normalize(c.providerCode());
		var provider = providers.require(code, ProviderCapability.CUSTOMER_PRESENT_QR,
				CustomerPresentQrProviderRpcService.class);
		var r = provider.inspectCustomerQr(new ProviderCustomerQrInspectCommand(UUID.randomUUID(), c.qrData(),
				c.deviceId(), c.shopId(), c.providerOptions()));
		if (r.outcome() != com.paymentgateway.common.ProviderOutcome.SUCCESS)
			throw new ProviderOperationRejectedException(code);
		return new CustomerQrInspectResult(r.providerCode(), r.customerPhone(), r.customerReference(),
				r.rawProviderDataJson(), r.responseCode(), r.responseMessage());
	}

	/**
	 * Executes the pay customer QR operation.
	 *
	 * @param c the customer QR pay command
	 * @return the resulting {@code CreatePaymentResult} value
	 */
	@Override
	public CreatePaymentResult payCustomerQr(CustomerQrPayCommand c) {
		Map<String, String> options = new HashMap<>();
		if (c.providerOptions() != null)
			options.putAll(c.providerOptions());
		options.put("customerPhone", c.customerPhone());
		options.put("paymentMethod", "PUSH");
		var create = new CreatePaymentCommand(c.merchantId(), c.merchantOrderId(), c.amountMinor(), c.currency(),
				c.description(), c.providerCode(), Map.copyOf(options), c.idempotencyKey(), c.requestHash());
		var reservation = persistence.reserveAndCreate(create);
		var current = persistence.require(reservation.paymentId());
		if (!reservation.created())
			return toCreateResult(current, "Idempotent customer-QR payment replay; provider initiation not repeated");
		var provider = providers.require(current.getSelectedProviderCode(), ProviderCapability.CUSTOMER_PRESENT_QR,
				CustomerPresentQrProviderRpcService.class);
		var result = provider.payCustomerQr(new ProviderCustomerQrPaymentCommand(current.getPaymentId(),
				current.getProviderOrderId(), current.getAmountMinor(), current.getCurrency(), c.customerPhone(),
				c.description(), Map.copyOf(options)));
		persistence.persistInitiateOutcome(current.getPaymentId(), result);
		return toCreateResult(persistence.require(current.getPaymentId()), result.responseMessage());
	}

	/**
	 * Lists providers.
	 *
	 * @return the matching values
	 */
	@Override
	public List<ProviderSummary> listProviders() {
		return providers.list();
	}

	/**
	 * Handles provider callback.
	 *
	 * @param c the provider callback command
	 * @return the resulting {@code CallbackAcceptance} value
	 */
	@Override
	public CallbackAcceptance handleProviderCallback(ProviderCallbackCommand c) {
		if (Boolean.FALSE.equals(c.signatureVerified()))
			throw new IllegalArgumentException("Provider callback authentication/signature verification failed");
		providers.require(c.providerCode(), ProviderCapability.CALLBACK, ProviderRpcService.class);
		String payload = c.providerCode() + "|" + c.callbackType() + "|" + c.orderId() + "|" + c.providerReferenceId()
				+ "|" + c.amount() + "|" + c.status() + "|" + c.transactionId();
		boolean first = persistence.recordCallback(c, Hashing.sha256(payload));
		var payment = persistence.findByProviderOrder(ProviderCode.normalize(c.providerCode()), c.orderId());
		if (!first)
			return new CallbackAcceptance(true, true, payment.getPaymentId(), payment.getStatus(),
					"Duplicate callback already recorded");
		try {
			payment = persistence.attachProviderReferenceIfAbsent(payment.getPaymentId(), c.providerReferenceId());
			var provider = providers.require(c.providerCode(), ProviderCapability.ENQUIRY, ProviderRpcService.class);
			var verified = provider.enquire(new EnquirePaymentCommand(payment.getPaymentId(),
					payment.getProviderOrderId(), payment.getProviderReferenceId(),
					payment.getProviderOrderDate() == null ? null : payment.getProviderOrderDate().toString(),
					payment.getProviderNextAction()));
			var updated = persistence.applyVerifiedEnquiry(payment, verified);
			String verifiedStatus = verified.providerTransactionStatus() == null ? verified.outcome().name()
					: String.valueOf(verified.providerTransactionStatus());
			persistence.markCallbackProcessed(c.providerCode(), c.dedupeKey(), verifiedStatus,
					"Callback recorded; provider status independently re-verified");
			return new CallbackAcceptance(true, false, updated.getPaymentId(), updated.getStatus(),
					"Callback recorded; provider status independently re-verified");
		} catch (RuntimeException e) {
			persistence.markCallbackFailed(c.providerCode(), c.dedupeKey(), e.getMessage());
			throw e;
		}
	}

	/**
	 * Executes the view operation.
	 *
	 * @param p the payment entity
	 * @return the resulting {@code PaymentView} value
	 */
	private static PaymentView view(com.paymentgateway.core.persistence.entity.PaymentEntity p) {
		return new PaymentView(p.getPaymentId(), p.getMerchantId(), p.getMerchantOrderId(), p.getAmountMinor(),
				p.getCurrency(), p.getStatus(), p.getQrPayload(), p.getSelectedProviderCode(), p.getProviderOrderId(),
				p.getProviderReferenceId(), action(p), p.getProviderRedirectUrl(), p.getProviderForm());
	}

	/**
	 * Converts the current value to create result.
	 *
	 * @param p   the payment entity
	 * @param msg the response message
	 * @return the resulting {@code CreatePaymentResult} value
	 */
	private static CreatePaymentResult toCreateResult(com.paymentgateway.core.persistence.entity.PaymentEntity p,
			String msg) {
		return new CreatePaymentResult(p.getPaymentId(), p.getStatus(), p.getQrPayload(), p.getSelectedProviderCode(),
				action(p), p.getProviderRedirectUrl(), p.getProviderForm(), msg);
	}

	/**
	 * Processes the refund for result.
	 *
	 * @param r   the refund or provider result
	 * @param msg the response message
	 * @return the resulting {@code RefundPaymentResult} value
	 */
	private static RefundPaymentResult refundResult(com.paymentgateway.core.persistence.entity.PaymentRefundEntity r,
			String msg) {
		return new RefundPaymentResult(r.getRefundId(), r.getPaymentId(), r.getMerchantRefundId(), r.getAmountMinor(),
				r.getCurrency(), r.getStatus(), r.getProviderCode(), r.getProviderRefundReferenceId(), msg);
	}

	/**
	 * Executes the action operation.
	 *
	 * @param p the payment entity
	 * @return the resulting action text
	 */
	private static String action(com.paymentgateway.core.persistence.entity.PaymentEntity p) {
		if (p.getProviderNextAction() != null && !p.getProviderNextAction().isBlank())
			return p.getProviderNextAction();
		if (p.getQrPayload() != null)
			return "DISPLAY_QR";
		if (p.getProviderRedirectUrl() != null)
			return "REDIRECT";
		return "NONE";
	}
}
