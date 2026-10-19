package com.paymentgateway.provider.abank;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import com.paymentgateway.audit.config.AuditPersistenceConfiguration;
import com.paymentgateway.provider.abank.http.AbankHttpService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Bootstraps the provider abank service Spring Boot application.
 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.paymentgateway.provider.abank")
@EnableConfigurationProperties(AbankProperties.class)
@Import(AuditPersistenceConfiguration.class)
@ImportHttpServices(group = "abank", types = AbankHttpService.class)
public class AbankProviderApplication {
    /**
     * Starts the abank provider application application.
     *
     * @param args the args value
     */
    public static void main(String[] args) {
        SpringApplication.run(AbankProviderApplication.class, args);
    }
}
