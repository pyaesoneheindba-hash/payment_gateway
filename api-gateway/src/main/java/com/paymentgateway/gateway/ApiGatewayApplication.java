package com.paymentgateway.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Bootstraps the api gateway Spring Boot application.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {
	/**
	 * Starts the API gateway application application.
	 *
	 * @param args the args value
	 */
	public static void main(String[] args) {
		SpringApplication.run(ApiGatewayApplication.class, args);
	}
}
