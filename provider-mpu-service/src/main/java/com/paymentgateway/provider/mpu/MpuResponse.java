package com.paymentgateway.provider.mpu;

import java.util.Map;

/**
 * Carries response data for mpu operations.
 */
public record MpuResponse(Map<String,String> values){
    /**
     * Executes the get operation.
     *
     * @param key the lookup or signing key
     * @return the resulting get text
     */
    public String get(String key){return values.get(key);}
}
