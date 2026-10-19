package com.paymentgateway.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies RocketMQ redelivery does not duplicate webhook fan-out. */
@Testcontainers(disabledWithoutDocker = true)
class PaymentStatusEventHandlerTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static JdbcTemplate jdbc;

    /**
     * Executes the setup operation.
     */
    @BeforeAll
    static void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE inbox_event(
              consumer_name VARCHAR(128) NOT NULL,
              event_id UUID NOT NULL,
              message_id VARCHAR(255),
              event_type VARCHAR(128) NOT NULL,
              received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              processed_at TIMESTAMPTZ,
              PRIMARY KEY(consumer_name,event_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE merchant_webhook_endpoint(
              endpoint_id UUID PRIMARY KEY,
              merchant_id UUID NOT NULL,
              event_type VARCHAR(128) NOT NULL,
              status VARCHAR(16) NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE webhook_delivery(
              delivery_id UUID PRIMARY KEY,
              event_id UUID NOT NULL,
              endpoint_id UUID NOT NULL,
              merchant_id UUID NOT NULL,
              payment_id UUID NOT NULL,
              event_type VARCHAR(128) NOT NULL,
              status VARCHAR(16) NOT NULL,
              attempt_count INTEGER NOT NULL,
              next_attempt_at TIMESTAMPTZ NOT NULL,
              version BIGINT NOT NULL,
              created_at TIMESTAMPTZ NOT NULL,
              updated_at TIMESTAMPTZ NOT NULL,
              UNIQUE(event_id,endpoint_id)
            )
            """);
    }


    /**
     * Executes the duplicate rocket mq delivery fans out only once operation.
     */
    @Test
    void duplicateRocketMqDeliveryFansOutOnlyOnce() {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO merchant_webhook_endpoint(endpoint_id,merchant_id,event_type,status) VALUES (?,?,?,?)",
                endpointId, merchantId, "PAYMENT_STATUS_CHANGED", "ACTIVE");

        PaymentStatusEventHandler handler = new PaymentStatusEventHandler(jdbc);
        PaymentLifecycleMessage message = new PaymentLifecycleMessage(
                eventId, "PAYMENT", paymentId, "PAYMENT_STATUS_CHANGED", 1,
                java.time.Instant.now(), Map.of("merchantId", merchantId.toString(), "status", "SUCCEEDED"));

        handler.handle(message, "rocket-message-1");
        handler.handle(message, "rocket-message-1-redelivery");

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_event", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM webhook_delivery", Integer.class));
    }
}
