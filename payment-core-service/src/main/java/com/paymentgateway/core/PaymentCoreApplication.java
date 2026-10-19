package com.paymentgateway.core;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Bootstraps the payment core service Spring Boot application.
 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.paymentgateway.core")
@EnableJpaAuditing
public class PaymentCoreApplication {
    /**
     * Starts the payment core application application.
     *
     * @param args the args value
     */
    public static void main(String[] args) {
        SpringApplication.run(PaymentCoreApplication.class, args);
    }
}
