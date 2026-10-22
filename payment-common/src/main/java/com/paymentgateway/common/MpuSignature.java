package com.paymentgateway.common;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MPU V2.8 signature helper: sort populated values using ordinal/case-sensitive order, concatenate, HMAC-SHA1,
 * uppercase hex.
 */
public final class MpuSignature {
    /**
     * Creates a new {@code MpuSignature} instance.
     */
    private MpuSignature() {
    }

    /**
     * Executes the sign operation.
     *
     * @param secretKey the secret key value
     * @param values    the values value
     * @return the resulting sign text
     */
    public static String sign(String secretKey, Collection<String> values) {
        if (secretKey == null || secretKey.isBlank())
            throw new IllegalStateException("MPU secret key is not configured");
        try {
            List<String> sorted = values.stream().filter(v -> v != null && !v.isEmpty())
                    .sorted(Comparator.naturalOrder()).toList();
            String signatureString = String.join("", sorted);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return HexFormat.of().withUpperCase()
                    .formatHex(mac.doFinal(signatureString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate MPU HMAC-SHA1", e);
        }
    }

    /**
     * Executes the verify operation.
     *
     * @param secretKey the secret key value
     * @param values    the values value
     * @param supplied  the supplied value
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    public static boolean verify(String secretKey, Collection<String> values, String supplied) {
        if (supplied == null || supplied.isBlank())
            return false;
        String expected = sign(secretKey, values);
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                supplied.trim().toUpperCase().getBytes(StandardCharsets.US_ASCII));
    }
}
