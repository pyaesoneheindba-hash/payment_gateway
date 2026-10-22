package com.paymentgateway.core.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.ProviderRpcService;

/**
 * Boot 4 + real Dubbo starter production-compatibility diagnostic.
 *
 * This test DOES NOT disable Dubbo Triple REST mapping. It deliberately uses
 * Spring Boot auto-configuration and @EnableDubbo, unlike the standalone
 * DubboBootstrap Stage 7.3B2 transport test.
 *
 * Only infrastructure isolation is configured: no live database, Nacos, real
 * bank provider, application container or Docker Compose interaction.
 */
@ActiveProfiles("stage7-boot4-compatibility")
@SpringBootTest(classes = Stage7ProductionDubboCompatibilitySmokeTest.IsolatedBootWithDubbo.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
		"spring.main.web-application-type=none", "spring.cloud.nacos.discovery.enabled=false",
		"spring.cloud.nacos.config.enabled=false", "spring.cloud.service-registry.auto-registration.enabled=false",
		"dubbo.registry.address=N/A", "dubbo.config-center.address=N/A",
		// No DB is needed to verify the Dubbo starter and its network service.
		// These are *infrastructure isolation* exclusions, not Dubbo workarounds.
		"spring.autoconfigure.exclude=" + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
				+ "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration" })
class Stage7ProductionDubboCompatibilitySmokeTest {
	private static final int PORT = freePort();

	@Autowired
	private Environment environment;

	@DynamicPropertySource
	static void isolatedEndpoints(DynamicPropertyRegistry properties) {
		properties.add("dubbo.application.name", () -> "stage7-boot4-dubbo-compatibility");
		properties.add("dubbo.protocol.name", () -> "tri");
		properties.add("dubbo.protocol.host", () -> "127.0.0.1");
		properties.add("dubbo.protocol.port", () -> Integer.toString(PORT));
		properties.add("dubbo.registry.address", () -> "N/A");
		properties.add("dubbo.config-center.address", () -> "N/A");
	}

	@Test
	void springBootExportsRealTripleRpcWithRestMappingAtDefault() throws Exception {
		// Critical: NO `-Ddubbo.protocol.triple.rest.enabled=false` allowed.
		assertNotEquals("false", System.getProperty("dubbo.protocol.triple.rest.enabled"),
				"This is a production-compatibility diagnostic; remove the REST-off JVM flag");
		assertNotEquals("false", environment.getProperty("dubbo.protocol.triple.rest.enabled"),
				"Production compatibility cannot be validated with Triple REST disabled");

		try (Socket connection = new Socket()) {
			connection.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
			assertTrue(connection.isConnected(), "Dubbo tri listener must be up");
		}

		// Force network mode even though test provider shares a JVM:
		// Spring Boot created/exported the provider; this is a real TCP call.
		ReferenceConfig<ProviderRpcService> ref = new ReferenceConfig<>();
		ref.setApplication(new ApplicationConfig("stage7-boot4-dubbo-compatibility-client"));
		ref.setRegistry(new RegistryConfig("N/A"));
		ref.setInterface(ProviderRpcService.class);
		ref.setProtocol("tri");
		ref.setUrl("tri://127.0.0.1:" + PORT);
		ref.setGroup("stage7-boot4-compatibility");
		ref.setVersion("1.0.0");
		ref.setTimeout(4000);
		ref.setRetries(0);
		ref.setScope("remote");
		ref.setCheck(true);
		try {
			ProviderRpcService client = ref.get();
			assertEquals("STAGE7-LOCAL", client.descriptor().providerCode());
			UUID paymentId = UUID.randomUUID();
			InitiatePaymentCommand command = new InitiatePaymentCommand(paymentId, "BOOT-COMPAT-" + paymentId,
					"PG-" + paymentId, 100L, "MMK", "No bank payment",
					Map.of("source", "spring-boot-compatibility-smoke"));
			var response = client.initiate(command);
			assertNotNull(response);
			assertEquals(ProviderOutcome.PENDING, response.outcome());
			assertEquals("STAGE7-LOCAL", response.providerCode());
			assertEquals("MOCK-QR-BOOT4", response.qr());
		} finally {
			ref.destroy();
		}
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
			return socket.getLocalPort();
		} catch (Exception exception) {
			throw new IllegalStateException("Cannot allocate local Dubbo test port", exception);
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableDubbo(scanBasePackages = "com.paymentgateway.core.compatibility")
	static class IsolatedBootWithDubbo {
		// Uses the actual project's Boot/Dubbo versions and normal
		// @DubboService auto-discovery, without production application scanning.
	}
}
