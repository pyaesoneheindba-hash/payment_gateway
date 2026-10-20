# Stage 7 — isolated payment lifecycle happy-path integration test

**Scope:** First Stage 7 test candidate. It exercises production `PaymentRpcServiceImpl` and `PaymentPersistenceService` with a **Mockito provider RPC adapter only**, then the production outbox relay, an isolated RocketMQ 5.3.2 Proxy broker, production RocketMQ consumer, transactional inbox, webhook delivery dispatcher, and a disposable localhost HTTP receiver that verifies Ed25519 `PGWH1` signatures and content digests.

**Not covered yet:** public Gateway HTTP/PGSIG1 authentication, actual Dubbo transport, actual bank callback endpoints, Redis/SSE, provider cryptography, multi-instance or fault-injection scenarios, DNS rebinding/egress hardening. This test does not prove those behaviors.

## Safety

- Runs via Maven/Testcontainers, NOT `docker compose`; no existing services, networks, topics, or persistent volumes are reused.
- PostgreSQL `postgres:16-alpine` is ephemeral and loaded from the project's real Flyway migrations.
- RocketMQ NameServer + Broker/Proxy use disposable containers and an isolated consumer group.
- No real bank adapter is instantiated, no bank HTTP API is called, and no existing merchant webhook endpoint is contacted.
- The webhook receiver binds only to `127.0.0.1`, and a new Ed25519 key pair is generated exclusively for the test.
- Test-only `allowHttp=true` and `allowPrivateAddresses=true` exist *only* to permit the localhost fixture; these must not be copied into production configuration.
- Docker must have enough resources to start a PostgreSQL container and two RocketMQ containers. A free dynamically selected TCP port is briefly bound for the RocketMQ Proxy advertised address.
- The test uses in-process `PaymentRpcServiceImpl` and a mocked `ProviderClientRegistry`: it is a **service integration test**, not yet a full system HTTP/Dubbo E2E test.

## Files

- Root `pom.xml`: add one Maven module, `stage7-integration-tests`, after `notification-service`.
- `stage7-integration-tests/pom.xml`: separate Maven test module; adds only dependencies, no runtime deployment configuration.
- `stage7-integration-tests/src/test/java/com/paymentgateway/notification/Stage7PaymentLifecycleTest.java`: test fixture and test.
- `stage7-integration-tests/src/test/resources/rocketmq/broker-it.conf`: isolated broker config derived from the already passing RocketMQ test.

## Package fix (revision 2)

- Corrected `parent-module.patch` hunk count, verified with `git apply --check` on a matching root Maven module section.
- Configured Mockito `-javaagent` in the Stage 7 test module, matching payment-core-service test configuration on Java 25.

## Local usage — Windows PowerShell

Copy/extract the folder into the project root and apply `parent-module.patch` after inspecting it:

```powershell
cd D:\payment-gateway-v20\v20work

# After copying STAGE7 files into the repository, check patch validity:
git apply --check .\parent-module.patch
git apply .\parent-module.patch

# Execute ONLY the Stage 7 test, building its module dependencies:
mvn -pl stage7-integration-tests -am clean test "-Dtest=Stage7PaymentLifecycleTest" "-Dsurefire.failIfNoSpecifiedTests=false"

# Review changes (do not commit until test passes):
git status --short
git diff --check
```

A successful test should show `Tests run: 1, Failures: 0, Errors: 0` in the `stage7-integration-tests` module and overall `BUILD SUCCESS`. Container startup can take several minutes.

**First-run troubleshooting:** If the build fails, share the **first error** and Maven reactor summary. Keep provider credentials, key material and full sensitive exception payloads out of logs shared with others. Do not edit your running Docker Compose stack to resolve this test's failures.
