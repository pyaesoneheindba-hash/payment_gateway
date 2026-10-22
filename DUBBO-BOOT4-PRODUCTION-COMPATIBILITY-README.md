# Stage 7: Spring Boot 4 + Dubbo 3.3.7-SNAPSHOT compatibility diagnostic

**Purpose:** test the real Spring Boot auto-configuration + `@EnableDubbo` +
`@DubboService` export path, over a real `tri://127.0.0.1` RPC connection,
**without changing/disabling Dubbo REST**.

This is NOT the Stage 7.3B2 bare `DubboBootstrap` test: the latter passed
only with Dubbo Triple REST mapping disabled. This one deliberately does not
set that flag. It runs in the `payment-core-service` test classpath, and
does not combine Gateway WebFlux with Payment API MVC in the same JVM.

It uses the actual Dubbo starter and project dependencies, a Spring Boot
context, a test-only `@DubboService` provider, and a Dubbo Triple client.
The test does NOT start `PaymentCoreApplication` or validate the
production database, real providers or Nacos. Startup/RPC success is a
necessary **first** compatibility check, not complete production certification.

Test resources are an in-memory Spring context and loopback TCP port only.
DB/JPA/Flyway, Nacos registration/metadata access are excluded solely because
this diagnostic isolates Dubbo and must not touch the running environment.
The test never sets `dubbo.protocol.triple.rest.enabled=false`.

## Apply

Copy/extract these two files under the repository root:

* `payment-core-service/src/test/java/com/paymentgateway/core/compatibility/Stage7ProductionDubboCompatibilitySmokeTest.java`
* `payment-core-service/src/test/java/com/paymentgateway/core/compatibility/Stage7LocalSpringDubboProvider.java`

No production code or Maven POM changes are included.

## Execute (PowerShell, at repo root)

```powershell
mvn -pl payment-core-service -am test `
  "-Dtest=Stage7ProductionDubboCompatibilitySmokeTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

**Do not** pass the REST-off property. Do **not** add the REST-off property
to `payment-core-service/pom.xml`. Do not run full `PaymentCoreApplication`
against the default `127.0.0.1:5432/payment_gateway` database.

If startup fails, share the first meaningful `Caused by:`, especially
a `NoSuchMethodError`, `ClassNotFoundException`, or Dubbo auto-configuration
exception. A failure is evidence of a compatibility problem to solve,
not a reason to disable Dubbo features.

If it passes, next validate the actual service wiring with disposable
Postgres/Nacos, the full multijvm Gateway/Payment API flow, and publication
of REST endpoints if Dubbo REST is a production requirement.

**This package has not been compiled or executed here**; its first run
may reveal framework/version-specific integration issues.
