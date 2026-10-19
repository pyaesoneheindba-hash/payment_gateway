package com.paymentgateway.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bootstraps the notification service Spring Boot application.
 */
@SpringBootApplication
@EnableScheduling
public class NotificationServiceApplication {
	/**
	 * Starts the notification service application application.
	 *
	 * @param args the args value
	 */
	public static void main(String[] args) {
		SpringApplication.run(NotificationServiceApplication.class, args);
	}
}
