package com.paymentgateway.provider.mpu;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Implements mpu response parser behavior for the payment gateway platform.
 */
public final class MpuResponseParser {
    /**
     * Creates a new {@code MpuResponseParser} instance.
     */
    private MpuResponseParser() {}
    /**
     * Executes the parse operation.
     *
     * @param body the request or response body
     * @return the resulting {@code MpuResponse} value
     */
    public static MpuResponse parse(String body){
        var map=new LinkedHashMap<String,String>();
        if(body==null) return new MpuResponse(map);
        for(String pair:body.trim().split("&")){
            if(pair.isBlank()) continue; int i=pair.indexOf('=');
            String k=i<0?pair:pair.substring(0,i); String v=i<0?"":pair.substring(i+1);
            map.put(URLDecoder.decode(k.trim(),StandardCharsets.UTF_8),URLDecoder.decode(v.trim(),StandardCharsets.UTF_8));
        }
        return new MpuResponse(map);
    }
}
