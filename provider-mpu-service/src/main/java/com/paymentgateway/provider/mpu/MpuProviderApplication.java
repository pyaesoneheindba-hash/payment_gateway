package com.paymentgateway.provider.mpu;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import com.paymentgateway.audit.config.AuditPersistenceConfiguration;
import com.paymentgateway.provider.mpu.http.MpuHttpService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Bootstraps the provider mpu service Spring Boot application.
 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.paymentgateway.provider.mpu")
@EnableConfigurationProperties(MpuProperties.class)
@Import(AuditPersistenceConfiguration.class)
@ImportHttpServices(group="mpu",types=MpuHttpService.class)
public class MpuProviderApplication {
    /**
     * Starts the mpu provider application application.
     *
     * @param args the args value
     */
    public static void main(String[] args){SpringApplication.run(MpuProviderApplication.class,args);}
}
