# Stage 7.3B2 — real Apache Dubbo Triple RPC transport

This is a **test-only** `Stage7DubboTripleTransportTest` in the existing
`stage7-integration-tests` Maven module. It uses Apache Dubbo's real
`ServiceConfig` exporter and `ReferenceConfig` client, **tri protocol**,
actual localhost TCP, and a local `ProviderRpcService` fake implementation.

## Scope

- `descriptor()` request / response crossing Dubbo transport.
- `initiate()` serializes a real `InitiatePaymentCommand`, including a UUID and
  provider option map, and returns an `InitiatePaymentResult`.
- `enquire()` serializes a real `EnquirePaymentCommand` and result.
- A deliberately slow mock `initiate()` is subject to a 250ms client timeout.
- The service is configured with `scope=remote` so tests cannot accidentally
  pass via Dubbo's in-JVM dispatch.

**Not covered:** Running Payment API / Payment Core, a live Gateway request,
actual provider adapters, PostgreSQL, RocketMQ, payment workflows, or
service registration through Nacos. Later Stage 7.3B3 will combine these.

## Isolation and safety

- All calls go exclusively to `tri://127.0.0.1:<ephemeral-port>`.
- `RegistryConfig("N/A")` on both sides; **Nacos is never used**.
- The test application exports **only a disposable fake `ProviderRpcService`**
  with a randomly generated test-only Dubbo group.
- No production bank credentials, HTTP endpoints or Docker services are used.
- No changes to production Java classes or root `pom.xml` are needed.
- `@AfterAll` destroys the Dubbo reference and unexports the local service.
- An available local TCP port is selected before the listener is exported;
  on a busy machine, a port-allocation race can still require a rerun.

## Run — Windows PowerShell in project root

```powershell
cd D:\payment-gateway-v20\v20work
mvn -pl stage7-integration-tests -am test `
  "-Dtest=Stage7DubboTripleTransportTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected on successful execution: **4 tests passed** and `BUILD SUCCESS`.
These Java tests have **not** been executed in the packaging environment.
If Maven fails, capture the first meaningful `COMPILATION ERROR`,
`Caused by:`, or test failure and the reactor summary. Do not modify Docker
Compose or bank adapter settings to troubleshoot this standalone test.
