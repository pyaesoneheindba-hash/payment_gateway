package com.paymentgateway.contract.error;

import com.paymentgateway.common.ProviderCapability;

/** Raised when the selected provider does not support a required payment capability. */
public final class ProviderCapabilityException extends PaymentGatewayException {
    private final String providerCode;
    private final ProviderCapability capability;

    /**
     * Creates an unsupported-provider-capability exception.
     *
     * @param providerCode normalized provider code
     * @param capability capability required by the requested operation
     */
    public ProviderCapabilityException(String providerCode, ProviderCapability capability) {
        super("UNSUPPORTED_PROVIDER_CAPABILITY",
                "Provider " + providerCode + " does not support " + capability);
        this.providerCode = providerCode;
        this.capability = capability;
    }

    /** @return normalized provider code */
    public String getProviderCode() {
        return providerCode;
    }

    /** @return unsupported capability required by the operation */
    public ProviderCapability getCapability() {
        return capability;
    }
}
