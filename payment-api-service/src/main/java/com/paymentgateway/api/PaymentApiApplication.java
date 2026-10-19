package com.paymentgateway.api;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import com.paymentgateway.audit.config.AuditPersistenceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bootstraps the payment api service Spring Boot application.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableDubbo(scanBasePackages = "com.paymentgateway.api")
@Import(AuditPersistenceConfiguration.class)
@EnableScheduling
public class PaymentApiApplication {
    /**
     * Starts the payment API application application.
     *
     * @param args the args value
     */
    public static void main(String[] args) { SpringApplication.run(PaymentApiApplication.class, args); }
}
