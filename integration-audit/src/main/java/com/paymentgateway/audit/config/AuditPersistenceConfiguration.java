package com.paymentgateway.audit.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.paymentgateway.audit.entity.ApiCallLogEntity;
import com.paymentgateway.audit.repo.ApiCallLogRepository;

/**
 * Configures audit persistence application components.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
@EntityScan(basePackageClasses = ApiCallLogEntity.class)
@EnableJpaRepositories(basePackageClasses = ApiCallLogRepository.class)
@ComponentScan(basePackages = "com.paymentgateway.audit.service")
public class AuditPersistenceConfiguration {
}
