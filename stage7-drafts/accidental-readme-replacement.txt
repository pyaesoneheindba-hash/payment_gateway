# Stage 7.3B3: invalid IPv4 loopback bind fix

Previous run now **compiles successfully**, but Core child fails while exporting
Dubbo service with: `Specified invalid bind ip from property:DUBBO_IP_TO_BIND, value:127.0.0.1`.

The Apache Dubbo `ServiceConfig.findConfiguredHosts` code rejects `127.*`
when supplied through DUBBO_IP_TO_BIND, while its Netty Triple server supports
IPv6 bind addresses and `::1` is not rejected by `NetUtils.isInvalidLocalHost`.

Apply this **test-only** patch to the separate-process Stage 7.3B3 fixtures.
It performs a Windows local IPv6 socket preflight, then backs up and edits
exactly three Java TEST files:

1. `Stage7CoreChild.java`: use `dubbo.protocol.host=::1`.
2. `Stage7PaymentApiChild.java`: use `tri://[::1]:<dynamic port>`.
3. `Stage7MultiJvmPaymentFlowTest.java`: delete the invalid
   `DUBBO_IP_TO_BIND=127.0.0.1` and `DUBBO_IP_TO_REGISTRY=127.0.0.1`
   child environment overrides. All test child environments continue removing
   host-provided DUBBO_, bank and application configuration.

**No fallback** to 0.0.0.0 or a real LAN/Wi-Fi interface. If IPv6 loopback is
unavailable, patch exits before editing and you must report the error.

## Commands (PowerShell from repository root)

```powershell
cd D:\payment-gateway-v20\v20work
Expand-Archive -Path "$env:USERPROFILE\Downloads\stage7-3b3-ipv6-loopback-fix.zip" -DestinationPath . -Force
& .\stage7-3b3\patch-ipv6-loopback.ps1
git diff --check
& .\stage7-3b3\run-stage7-3b3.ps1
```

The existing `run-stage7-3b3.ps1` from the **clean-build fix** is retained.
The patch does not touch production Java/POM, databases, Docker containers,
Nacos, webhooks or any bank endpoint. Only the isolated test Core child binds
Dubbo on IPv6 loopback; Gateway and Payment API HTTP remain on IPv4 loopback.

## What to report

If test fails again, share first `Caused by` and child log lines around it,
under `stage7-integration-tests/target/stage7-3b3-logs/<run-id>/`.
Do not commit until full end-to-end tests actually pass.

**This patch has not yet been run on your Windows/JDK 27 machine.** A subsequent
failure may arise from other integration issues. It is not a reason to disable
REST mapping or change production behavior.
