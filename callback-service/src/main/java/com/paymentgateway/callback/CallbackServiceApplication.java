package com.paymentgateway.callback;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

import com.paymentgateway.audit.config.AuditPersistenceConfiguration;

/**
 * Bootstraps the callback service Spring Boot application.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableDubbo(scanBasePackages = "com.paymentgateway.callback")
@Import(AuditPersistenceConfiguration.class)
@EnableConfigurationProperties({ MpuCallbackProperties.class, AyaPayCallbackProperties.class })
public class CallbackServiceApplication {
	/**
	 * Starts the callback service application application.
	 *
	 * @param args the args value
	 */
	public static void main(String[] args) {
		SpringApplication.run(CallbackServiceApplication.class, args);
	}
}
