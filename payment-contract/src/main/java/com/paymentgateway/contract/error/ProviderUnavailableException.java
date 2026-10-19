package com.paymentgateway.contract.error;

/**
 * Raised when a configured provider is temporarily unavailable or internally
 * inconsistent.
 */
public final class ProviderUnavailableException extends PaymentGatewayException {
	private static final long serialVersionUID = -6545868935261362119L;
	private final String providerCode;

	/**
	 * Creates a provider-unavailable exception.
	 *
	 * @param providerCode normalized provider code
	 * @param message      safe description of the temporary provider failure
	 */
	public ProviderUnavailableException(String providerCode, String message) {
		super("PROVIDER_UNAVAILABLE", message);
		this.providerCode = providerCode;
	}

	/**
	 * Creates a provider-unavailable exception with an underlying cause.
	 *
	 * @param providerCode normalized provider code
	 * @param message      safe description of the temporary provider failure
	 * @param cause        underlying failure for server-side diagnostics
	 */
	public ProviderUnavailableException(String providerCode, String message, Throwable cause) {
		super("PROVIDER_UNAVAILABLE", message, cause);
		this.providerCode = providerCode;
	}

	/** @return normalized provider code */
	public String getProviderCode() {
		return providerCode;
	}
}
