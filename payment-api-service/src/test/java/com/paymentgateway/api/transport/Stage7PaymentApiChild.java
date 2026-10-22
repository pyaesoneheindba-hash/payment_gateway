package com.paymentgateway.api.transport;

import java.util.concurrent.CountDownLatch;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.paymentgateway.api.checkout.CheckoutSessionService;
import com.paymentgateway.api.web.ApiExceptionHandler;
import com.paymentgateway.api.web.MerchantPaymentController;

/** Actual Spring MVC payment controller, actual Dubbo @DubboReference, own JVM. */
public final class Stage7PaymentApiChild {
    /** Starts the MVC Payment API in its own process with a direct local Triple reference.
     * @param args API HTTP port followed by the local Core Triple port
     * @throws Exception if the isolated API cannot start
     */
    public static void main(String[] args) throws Exception {
        if ("false".equalsIgnoreCase(System.getProperty("dubbo.protocol.triple.rest.enabled")))
            throw new IllegalStateException("Production compatibility test forbids REST-disabled JVM");
        if (args.length != 2) throw new IllegalArgumentException("apiPort coreRpcPort required");
        int apiPort = Integer.parseInt(args[0]);
        int corePort = Integer.parseInt(args[1]);
        // The test uses Dubbo's documented per-interface direct-address override.
        // Crucially, the production @DubboReference fields remain untouched.
        String serviceKey = "dubbo.reference.com.paymentgateway.contract.payment.PaymentRpcService.url";
        String serviceUrl = "tri://[::1]:" + corePort;
        try (var ignored = new SpringApplicationBuilder(ApiBoot.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                    "spring.config.name=stage7b3-no-production-config",
                    "spring.application.name=stage7b3-local-payment-api",
                    "server.address=127.0.0.1",
                    "server.port=" + apiPort,
                    "spring.cloud.nacos.discovery.enabled=false",
                    "spring.cloud.nacos.config.enabled=false",
                    "spring.cloud.discovery.enabled=false",
                    "spring.cloud.service-registry.auto-registration.enabled=false",
                    "spring.data.redis.repositories.enabled=false",
                    "dubbo.application.name=stage7b3-local-payment-api",
                    "dubbo.application.qos-enable=false",
                    "dubbo.registry.address=N/A",
                    "dubbo.registry.use-as-config-center=false",
                    "dubbo.registry.use-as-metadata-center=false",
                    "dubbo.consumer.check=true",
                    serviceKey + "=" + serviceUrl,
                    "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration")
                .run()) {
            System.out.println("STAGE7_API_READY:" + apiPort);
            new CountDownLatch(1).await();
        }
    }

    /** Configures the real payment controller with isolated supporting dependencies. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableDubbo(scanBasePackages = "com.paymentgateway.api.transport")
    @Import({MerchantPaymentController.class, CheckoutSessionService.class, ApiExceptionHandler.class})
    static class ApiBoot {
        // Only QR_STRING is tested: CheckoutSessionService calls the real Dubbo
        // configureCheckout RPC. JDBC stays deliberately unconnected, since
        // HOSTED_PAGE isn't in this test and must be covered separately.
        /** Provides a nonconnecting JDBC dependency for QR-only checkout testing.
         * @return JDBC template backed by an inert test data source
         */
        @Bean JdbcTemplate isolatedJdbc() {
            // QR_STRING does not use CheckoutSessionService JDBC.
            // Use an inert DataSource solely to satisfy JdbcTemplate initialization.
            // No database connection can be opened by this mocked DataSource.
            return new JdbcTemplate(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        }
    }
}
