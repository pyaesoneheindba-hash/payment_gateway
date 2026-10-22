# Stage 7.3B3 — Isolated HTTP Gateway → MVC API → Dubbo → real Payment Core

This is the first end-to-end **payment creation** boundary test. It launches:
* Temporary PostgreSQL (`postgres:16-alpine`) with the project’s **actual Flyway schema**
* Production `PaymentRpcServiceImpl` and `PaymentPersistenceService`
* An actual localhost Apache Dubbo Triple (`tri`) RPC listener and TCP client
* An embedded Spring MVC service exposing the production `MerchantPaymentController`
* A real HTTP Spring Cloud Gateway server exposing the production PGSIG1 `GlobalFilter`
* A synthetic `ProviderRpcService` *only*, which never accesses a bank network

Both HTTP servers bind `127.0.0.1` on ephemeral ports. The Dubbo listener binds
`127.0.0.1` on an unused transient port. A literal direct Dubbo URI and HTTP Gateway
URI are used: **no Nacos discovery/registration**.

Only test collaborators are mocked:
* PGSIG1 merchant key lookup, replay cache, security-audit persistence (previously
  tested separately)
* `CheckoutSessionService.applyPaymentExpiry` (separate checkout boundary)
* The bank `ProviderRpcService` (never ABank/AYA/MPU network clients)

The real Payment API `MerchantPaymentController` is used with an explicitly injected
*actual networked* Dubbo reference. The test does **not** exercise Dubbo's Spring
`@DubboReference` auto-injection or Spring's production service discovery. These remain
separate deployment tests.

Expected tests:
1. Signed POST returns HTTP 200 and creates a real PostgreSQL payment/outbox.
2. Two signed requests with the same idempotency key but different nonces do not
   reinitiate the fake bank.
3. Invalid signed request is rejected at Gateway; no payment is persisted.

## Apply

From PowerShell at `D:\payment-gateway-v20\v20work`, extract the ZIP into the
project root. This package adds the Java test and README, plus one **test-module**
Maven dependency to `payment-api-service`. Review `git diff` before executing.

## Execute

```powershell
mvn -pl stage7-integration-tests -am test `
  "-Dtest=Stage7GatewayApiCoreHttpDubboTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

The existing Surefire test module setting
`dubbo.protocol.triple.rest.enabled=false` is required and should remain
restricted to the test module.

### Verification and troubleshooting

Expected **if the new test is compatible and passes**: three JUnit tests,
zero failures, `BUILD SUCCESS`, with an ephemeral PostgreSQL container.
The implementation has not been compiled/run in this packaging environment.
If Maven fails, share **the first meaningful `Caused by:` exception** and reactor
summary. Do not make changes to live Docker Compose, Nacos, real merchant keys,
or bank endpoints as a workaround.

This is not yet a full deployment-level E2E test: no webhook/RocketMQ
integration in the *same* request chain, no live provider adapters, no external
Keycloak authentication, and no actual checkout-expiry implementation.
