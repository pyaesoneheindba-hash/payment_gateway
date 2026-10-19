package com.paymentgateway.contract.error;

/** Raised when a provider explicitly rejects an otherwise valid operation. */
public final class ProviderOperationRejectedException extends PaymentGatewayException {
    private final String providerCode;

    /**
     * Creates a provider-rejected exception.
     *
     * @param providerCode normalized provider code that rejected the operation
     */
    public ProviderOperationRejectedException(String providerCode) {
        super("PROVIDER_REJECTED", "Provider rejected the requested operation");
        this.providerCode = providerCode;
    }

    /** @return normalized provider code */
    public String getProviderCode() {
        return providerCode;
    }
}
