# Stage 7.3A — PGSIG1 Gateway filter boundary tests

This incremental package adds **seven test cases** around the real `ClientRequestSignatureFilter` and the existing PGSIG1 cryptographic primitives. It does not change production source code, Docker Compose, database migrations, or merchant/provider configuration.

## Scope

Checks: valid signed request gets through the filter with stripped/verified headers and unchanged body; invalid signature returns 401; modified body returns 400; reused nonce returns 409; stale timestamp returns 401; missing signature metadata returns 400; unknown key returns 401. The downstream payment service is represented by an in-memory `GatewayFilterChain` counting invocations.

**Important boundary:** MockServerWebExchange is an in-process simulated HTTP request, not a live Gateway HTTP listener. Signing-key lookup, replay storage, and audit persistence are mocked. These cases do **not** test the production Redis/PostgreSQL connections, networked HTTP routing, Nacos/Dubbo, or the business payment creation API. A later Stage 7.3B will add those transport-level checks with disposable infrastructure.

## Changes

- `stage7-integration-tests/pom.xml` — adds dependency on existing `api-gateway` module (still a test-only consumer; no new publisher/consumer).
- `stage7-integration-tests/src/test/java/com/paymentgateway/gateway/security/Stage7GatewaySignatureFilterTest.java` — seven filter test cases.

## Apply safely (Windows PowerShell, from repo root)

```powershell
cd D:\payment-gateway-v20\v20work
# Confirm only previously generated temporary parent-module.patch is untracked:
git status --short
# Extract this ZIP over your working tree; it replaces only test module pom and adds one new test file.
Expand-Archive -Path "$env:USERPROFILE\Downloads\stage7-3-gateway-filter-tests.zip" -DestinationPath . -Force
# Review before running:
git diff -- stage7-integration-tests/pom.xml
git status --short
# Run *only* the new seven tests in the Stage 7 test module:
mvn -pl stage7-integration-tests -am test "-Dtest=Stage7GatewaySignatureFilterTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

No `docker compose down`, `docker compose up`, database update, or new provider configuration is required.

**Verification note:** The package's file layout and XML have been verified by the packager. Java 25/Maven runtime execution is **not available** in the packager environment, so the test outcome must be confirmed on your laptop before any Git commit. If a test fails, share the first Maven compile/test error rather than committing.
