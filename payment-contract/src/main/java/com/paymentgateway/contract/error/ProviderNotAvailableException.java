package com.paymentgateway.contract.error;

/** Raised when a provider is not enabled or configured for use. */
public final class ProviderNotAvailableException extends PaymentGatewayException {
	private static final long serialVersionUID = 1017677539421084043L;
	private final String providerCode;

	/**
	 * Creates a provider-not-available exception.
	 *
	 * @param providerCode normalized provider code requested by the caller
	 */
	public ProviderNotAvailableException(String providerCode) {
		super("PROVIDER_NOT_AVAILABLE", "Provider is not enabled or configured: " + providerCode);
		this.providerCode = providerCode;
	}

	/** @return normalized provider code */
	public String getProviderCode() {
		return providerCode;
	}
}
