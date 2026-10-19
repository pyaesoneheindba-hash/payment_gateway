package com.paymentgateway.gateway.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Verifies the behavior of client request crypto.
 */
class ClientRequestCryptoTest {
    /**
     * Executes the verifies digest and ed25519 signature operation.
     *
     * @throws Exception if  processing fails
     */
    @Test
    void verifiesDigestAndEd25519Signature() throws Exception {
        byte[] body = "{\"amountMinor\":2000}".getBytes(StandardCharsets.UTF_8);
        String digest = ClientRequestCrypto.contentDigest(body);
        assertTrue(ClientRequestCrypto.verifyContentDigest(digest, body));
        assertFalse(ClientRequestCrypto.verifyContentDigest(digest, "changed".getBytes(StandardCharsets.UTF_8)));

        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String canonical = ClientRequestCrypto.canonicalize(
                "POST", "/api/v1/payments", "", "11111111-1111-1111-1111-111111111111",
                "merchant-key-2026-01", "1791789000", "64e49e22-94d9-41ab-8cf0-081704fa1542",
                "abank-test-001", digest);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        assertTrue(ClientRequestCrypto.verifyEd25519(publicKey, canonical, signature));
        assertFalse(ClientRequestCrypto.verifyEd25519(publicKey, canonical + "x", signature));
    }
}
