package com.paymentgateway.contract.payment;

import java.util.List;
import java.util.UUID;

/**
 * Defines operations provided by the payment RPC service.
 */
public interface PaymentRpcService {
	/**
	 * Creates payment.
	 *
	 * @param command the operation command
	 * @return the resulting {@code CreatePaymentResult} value
	 */
	CreatePaymentResult createPayment(CreatePaymentCommand command);

	/**
	 * Retrieves payment.
	 *
	 * @param merchantId the merchant identifier
	 * @param paymentId  the payment identifier
	 * @return the resulting {@code PaymentView} value
	 */
	PaymentView getPayment(UUID merchantId, UUID paymentId);

	/**
	 * Reconciles payment.
	 *
	 * @param merchantId the merchant identifier
	 * @param paymentId  the payment identifier
	 * @return the resulting {@code PaymentView} value
	 */
	PaymentView reconcilePayment(UUID merchantId, UUID paymentId);

	/**
	 * Configures checkout.
	 *
	 * @param merchantId          the merchant identifier
	 * @param paymentId           the payment identifier
	 * @param presentationMode    the presentation mode value
	 * @param expiresAtEpochMilli the expires at epoch milli value
	 * @return the resulting {@code PaymentView} value
	 */
	PaymentView configureCheckout(UUID merchantId, UUID paymentId, String presentationMode, long expiresAtEpochMilli);

	/**
	 * Expires payment.
	 *
	 * @param merchantId the merchant identifier
	 * @param paymentId  the payment identifier
	 * @return the resulting {@code PaymentView} value
	 */
	PaymentView expirePayment(UUID merchantId, UUID paymentId);

	/**
	 * Processes the refund for payment.
	 *
	 * @param command the operation command
	 * @return the resulting {@code RefundPaymentResult} value
	 */
	RefundPaymentResult refundPayment(RefundPaymentCommand command);

	/**
	 * Voids payment.
	 *
	 * @param command the operation command
	 * @return the resulting {@code PaymentView} value
	 */
	PaymentView voidPayment(VoidPaymentCommand command);

	/**
	 * Executes the inspect customer QR operation.
	 *
	 * @param command the operation command
	 * @return the resulting {@code CustomerQrInspectResult} value
	 */
	CustomerQrInspectResult inspectCustomerQr(CustomerQrInspectCommand command);

	/**
	 * Executes the pay customer QR operation.
	 *
	 * @param command the operation command
	 * @return the resulting {@code CreatePaymentResult} value
	 */
	CreatePaymentResult payCustomerQr(CustomerQrPayCommand command);

	/**
	 * Lists providers.
	 *
	 * @return the matching values
	 */
	List<ProviderSummary> listProviders();

	/**
	 * Handles provider callback.
	 *
	 * @param command the operation command
	 * @return the resulting {@code CallbackAcceptance} value
	 */
	CallbackAcceptance handleProviderCallback(ProviderCallbackCommand command);
}
