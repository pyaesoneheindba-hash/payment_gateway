# Stage 7.3B1 — live HTTP Spring Cloud Gateway routing

This adds a standalone `@SpringBootTest` that starts a **real local reactive
Spring Cloud Gateway HTTP server** on an ephemeral port, routes `POST /api/v1/payments`
to a local loopback-only disposable HTTP stub, and exercises the **production**
`ClientRequestSignatureFilter` for six HTTP scenarios.

The stub does not initiate, persist or settle payments; it records only test
request headers and returns HTTP 202. Signing-key lookup, nonce reservation and
security-audit storage are mocked **only inside this test context**. Therefore
this is a live Gateway HTTP-routing boundary test, **not yet** a Payment API +
networked Dubbo or full-system E2E test.

Safety:
* No real ABank, AYA Pay or MPU adapter is started or called.
* No access to the payment Docker Compose stack, existing DB, Nacos or Redis.
* The test application configuration is loaded from `stage7b-test.properties`
  (not the production `application.yml`). Nacos discovery and R2DBC autoconfig
  are disabled in this standalone test.
* Both Gateway HTTP listener and downstream stub bind to 127.0.0.1 on ephemeral
  ports; the only outgoing requests go to that stub.
* A new Ed25519 keypair is generated in memory for this test and is never stored.
* No production code, provider configuration, database or endpoints change.

### Run on Windows PowerShell from project root

```powershell
cd D:\payment-gateway-v20\v20work
mvn -pl api-gateway -am test "-Dtest=Stage7LiveGatewayHttpTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected when compatible: 6 tests passed, 0 failures, 0 errors.
The Java code has not been compiled/executed in the packaging environment;
if the first Maven run fails, preserve the first error and reactor summary
for diagnosis before proceeding. Do not test against your running payment stack.

### Next milestone

Stage 7.3B2: independently test genuine networked Dubbo tri RPC using a mock
provider and isolated Payment Core. Stage 7.3B3: connect the live HTTP Gateway
to isolated Payment API + Dubbo Core to cover the full external path.
