package com.paymentgateway.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentgateway.contract.error.IdempotencyConflictException;
import com.paymentgateway.contract.error.InvalidPaymentStateException;
import com.paymentgateway.contract.error.PaymentNotFoundException;
import com.paymentgateway.contract.error.ProviderCapabilityException;
import com.paymentgateway.contract.payment.DuplicateMerchantOrderException;
import com.paymentgateway.common.ProviderCapability;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Verifies the behavior of API exception handler.
 */
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    /**
     * Executes the duplicate order is conflict with stable code and correlation identifier operation.
     */
    @Test
    void duplicateOrderIsConflictWithStableCodeAndCorrelationId() {
        MockHttpServletRequest request = request("POST", "/api/v1/payments");
        request.addHeader("X-Correlation-Id", "corr-123");

        var response = handler.domain(new DuplicateMerchantOrderException("ORDER-1"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("DUPLICATE_MERCHANT_ORDER");
        assertThat(response.getBody().correlationId()).isEqualTo("corr-123");
        assertThat(response.getBody().details()).containsEntry("merchantOrderId", "ORDER-1");
    }

    /**
     * Executes the payment not found is404 operation.
     */
    @Test
    void paymentNotFoundIs404() {
        var response = handler.domain(new PaymentNotFoundException(UUID.randomUUID()),
                request("GET", "/api/v1/payments/123"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("PAYMENT_NOT_FOUND");
    }

    /**
     * Executes the idempotency conflict is409 operation.
     */
    @Test
    void idempotencyConflictIs409() {
        var response = handler.domain(new IdempotencyConflictException(), request("POST", "/api/v1/payments"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    /**
     * Executes the invalid payment state is409 operation.
     */
    @Test
    void invalidPaymentStateIs409() {
        var response = handler.domain(new InvalidPaymentStateException("Payment cannot be refunded in this state"),
                request("POST", "/api/v1/payments/1/refunds"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("INVALID_PAYMENT_STATE");
    }

    /**
     * Executes the unsupported capability is422 operation.
     */
    @Test
    void unsupportedCapabilityIs422() {
        var response = handler.domain(new ProviderCapabilityException("ABANK_APLUS", ProviderCapability.REFUND),
                request("POST", "/api/v1/payments/1/refunds"));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_PROVIDER_CAPABILITY");
        assertThat(response.getBody().details()).containsEntry("providerCode", "ABANK_APLUS");
    }

    /**
     * Executes the unknown exception does not leak internal message operation.
     */
    @Test
    void unknownExceptionDoesNotLeakInternalMessage() {
        var response = handler.unexpected(new RuntimeException("password=super-secret jdbc:postgresql://db"),
                request("GET", "/api/v1/payments/1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("super-secret").doesNotContain("jdbc");
    }

    /**
     * Executes the request operation.
     *
     * @param method the method value
     * @param path the path value
     * @return the resulting {@code MockHttpServletRequest} value
     */
    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
