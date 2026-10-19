package com.paymentgateway.gateway.security.publictoken;

/**
 * Signals a public token failure.
 */
public class PublicTokenException extends RuntimeException {
	private final String code;

	/**
	 * Creates a new {@code PublicTokenException} instance.
	 *
	 * @param code    the provider or status code
	 * @param message the message value
	 */
	public PublicTokenException(String code, String message) {
		super(message);
		this.code = code;
	}

	/**
	 * Creates a new {@code PublicTokenException} instance.
	 *
	 * @param code    the provider or status code
	 * @param message the message value
	 * @param cause   the cause value
	 */
	public PublicTokenException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	/**
	 * Executes the code operation.
	 *
	 * @return the resulting code text
	 */
	public String code() {
		return code;
	}
}
