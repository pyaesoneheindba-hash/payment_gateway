# Stage 7.3B3 — Separate-JVM Gateway → MVC API → Dubbo Core

## Status

**Candidate test package — not yet compiled or executed.** The project is
available via remote source inspection but this environment does not have its
JDK 27 / Maven dependencies / Docker Testcontainers runtime. Do not commit
before local results are checked.

The user confirmed `mvn -pl api-gateway,payment-api-service,payment-core-service -am package -DskipTests`
passes. The first Stage 7.3B3 prototype failed because a single JVM had both
Spring Cloud Gateway WebFlux and Spring MVC dependencies. This package fixes
**the process/classpath architecture**, not the production business logic.

## Architecture (real three network hops)

- `api-gateway` child JVM: actual Spring Cloud Gateway WebFlux route + actual
  `ClientRequestSignatureFilter` (PGSIG1). Signing key, replay-cache and
  security-audit repositories are **in-memory test fakes** only. Not production
  authentication persistence.
- `payment-api-service` child JVM: **actual** `MerchantPaymentController`,
  `ApiExceptionHandler`, `CheckoutSessionService`, Spring MVC and Dubbo
  `@DubboReference` fields. Uses Dubbo's documented **per-interface direct
  URL override**, not a patched controller, fake RPC proxy or changed annotation.
  QR_STRING expiry calls Core over RPC. JDBC exists unconnected; HOSTED_PAGE
  checkout and redirect URI verification are explicitly outside this slice.
- `payment-core-service` child JVM: **actual** annotated
  `PaymentRpcServiceImpl` exported by Spring + Dubbo (`tri`), actual
  `PaymentPersistenceService`, JPA repositories, schema migration and an
  isolated Testcontainers `postgres:16-alpine` database. `ProviderClientRegistry`
  is replaced with a **test-only Mockito stub** that returns a synthetic bank
  response; no real bank HTTP/RPC code is invoked.
- `stage7-integration-tests` JUnit orchestrator launches/terminates each child,
  signs HTTP requests using a fresh Ed25519 keypair, and verifies rows/outbox
  in the disposable Postgres through a loopback-only diagnostics endpoint.

Tests: signed creation, idempotency with different nonces, invalid PGSIG1 signature.
No Docker Compose commands, production Nacos, production PostgreSQL, bank
sandbox or merchant webhook endpoints are used. A **disposable Docker
container** is required for Testcontainers. The ports are allocated locally.

## Apply

From repo root `D:\payment-gateway-v20\v20work`, extract this ZIP with
`Expand-Archive` to `-DestinationPath .`.

This adds **four test Java files plus this PowerShell script and README**.
It does **not** overwrite POMs, production `src/main`, application YAML,
Docker, Nacos configuration or the archived draft test. It does not modify
Git branches or commit.

## Run

```powershell
cd D:\payment-gateway-v20\v20work

& .\stage7-3b3\run-stage7-3b3.ps1
```

The runner compiles **and installs into the local Maven repository only**
the selected reactor modules (with execution of prior unit tests skipped for
this preparation step), obtains each child project's Maven `test` classpath,
then runs the single Stage 7.3B3 suite. The test creates each child in its
**own JVM with its own module classpath**; it does not combine the WebFlux
Gateway and Spring MVC API dependency sets.

The existing `stage7-integration-tests/pom.xml` still has a **legacy Surefire
REST-disabled flag** from Stage 7.3B2. This is a tracked blocker: **none of
these three child JVMs receives that flag** and each child fails immediately
if it is passed. After the new tests run, replace the old RPC test with a
Spring Boot integration version and remove that legacy flag; do not call Stage
7 fully production-approved until then.

To run Maven from an arbitrary directory, the script always switches to the
repo root. It checks exit codes and stops before launching any processes if
compilation or classpath resolution fails.

## Evidence / limitations

Child logs are in:

`stage7-integration-tests/target/stage7-3b3-logs/<test-run-uuid>/{core,api,gateway}.log`

`mvn` may report a compile or startup error because **the package has not yet
been executed against this project's JDK 27**. The correct response is to
investigate it, **not disable REST mappings or WebFlux/MVC to pass tests**.

Direct Dubbo provider URL bypasses Nacos discovery **only to protect your
running environment**. A separate later integration suite must verify Nacos
registration/discovery and real service configurations, against isolated
instances. Full payment lifecycle with RocketMQ/webhooks is in separate Stage
7.2 tests; this is the first HTTP→RPC slice.

The test-only security chain skips OIDC authentication to isolate PGSIG1;
production public client/portal auth must be tested independently. Real bank
providers and merchant webhooks must never be invoked in this test.

**Network binding caveat:** Gateway and API use `server.address=127.0.0.1`.
The Core child sets `DUBBO_IP_TO_BIND=127.0.0.1` and
`DUBBO_IP_TO_REGISTRY=127.0.0.1`; verify in `core.log` that its Triple Netty
listener does not bind to `0.0.0.0`. The Testcontainers postgres port binds
according to the local Docker/Testcontainers implementation; it is disposable
and not part of the running Compose stack.

## Safety

Do not run the production executable JARs independently for this scenario:
their default `application.yml` can point to `127.0.0.1:5432/payment_gateway`
and real provider UAT endpoints. Use only this **test-class fixture** runner.
Do not push or commit before obtaining and inspecting the test results.
