package com.paymentgateway.provider.ayapay;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.web.service.registry.ImportHttpServices;

import com.paymentgateway.audit.config.AuditPersistenceConfiguration;
import com.paymentgateway.provider.ayapay.http.AyaPayHttpService;

/**
 * Bootstraps the provider ayapay service Spring Boot application.
 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.paymentgateway.provider.ayapay")
@EnableConfigurationProperties(AyaPayProperties.class)
@Import(AuditPersistenceConfiguration.class)
@ImportHttpServices(group = "ayapay", types = AyaPayHttpService.class)
public class AyaPayProviderApplication {
	/**
	 * Starts the aya pay provider application application.
	 *
	 * @param args the args value
	 */
	public static void main(String[] args) {
		SpringApplication.run(AyaPayProviderApplication.class, args);
	}
}
