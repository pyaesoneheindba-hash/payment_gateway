package com.paymentgateway.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AYA PAY callback decryption as documented: AES/ECB with PKCS#7 padding and UTF-8 key bytes.
 */
public final class AyaPayCrypto {
    /**
     * Creates a new {@code AyaPayCrypto} instance.
     */
    private AyaPayCrypto() {
    }

    /**
     * Executes the decrypt payment result operation.
     *
     * @param base64CipherText the base64 cipher text value
     * @param decryptionKey    the decryption key value
     * @return the resulting decrypt payment result text
     */
    public static String decryptPaymentResult(String base64CipherText, String decryptionKey) {
        if (base64CipherText == null || base64CipherText.isBlank()) {
            throw new IllegalArgumentException("AYA PAY paymentResult is required");
        }
        if (decryptionKey == null || decryptionKey.isBlank()) {
            throw new IllegalArgumentException("AYA PAY decryption key is not configured");
        }
        try {
            byte[] key = decryptionKey.getBytes(StandardCharsets.UTF_8);
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalArgumentException("AYA PAY AES key must be 16, 24, or 32 UTF-8 bytes");
            }
            // Java's PKCS5Padding implementation is PKCS#7-compatible for AES's 16-byte
            // block size.
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return new String(cipher.doFinal(Base64.getDecoder().decode(base64CipherText)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to decrypt AYA PAY callback paymentResult", e);
        }
    }
}
