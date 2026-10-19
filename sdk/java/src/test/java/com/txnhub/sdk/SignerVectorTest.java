package com.txnhub.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies the behavior of signer vector.
 */
class SignerVectorTest {
    /**
     * Executes the matches published conformance vector operation.
     */
    @Test
    void matchesPublishedConformanceVector() {
        String digest = "sha-256=:rrC/iFN43j9d9x5nLP9mU5+OKbyhhez+dSsvfjE+SAU=:";
        String canonical = TxnHubSigner.canonical("POST", "/api/v1/payments", "",
                "11111111-1111-1111-1111-111111111111", "sdk-test-key-01", "1791965356",
                "11111111-2222-4333-8444-555555555555", "sdk-vector-001", digest);
        assertEquals("PGSIG1\nPOST\n/api/v1/payments\n\n11111111-1111-1111-1111-111111111111\nsdk-test-key-01\n1791965356\n11111111-2222-4333-8444-555555555555\nsdk-vector-001\n" + digest, canonical);
        String signature = new TxnHubSigner(Path.of("test-vectors/test-private-key.pem")).sign(canonical);
        assertEquals("FGAhMbILEFWE7OYzQmfmXZ4dzpsKoRsSIxPMeDRHW4gN78sTHf2Zrp8o/3+15WJ2r9mLYRWSfux4JlXvRd2bBg==", signature);
    }
}
