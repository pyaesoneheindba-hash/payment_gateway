# Stage 7.3B3 QR fixture fix (test-only)

**Observed:** `signedRequestCreatesPaymentThroughAllThreeJvms` returns HTTP 200 / PENDING with null QR, fails at line 124 asserting presence of `LOCAL-QR-...`.

**Cause:** The synthetic provider currently returns `InitiatePaymentResult(ProviderOutcome.PENDING, ...)` with an immediate QR. The production `PaymentPersistenceService.persistInitiateOutcome()` intentionally ignores `r.qr()` on PENDING; it persists QR and marks `QR_GENERATED` on SUCCESS with nonempty QR.

**Change:** Make the synthetic provider report `ProviderOutcome.SUCCESS` (successful *QR initiation*, not customer payment settlement) and update only the Stage7MultiJvmPaymentFlowTest to assert `QR_GENERATED` and an explicit `qr` field. Keeps production persistence and all features unchanged.

**Apply:** From repo root, extract the ZIP over the repository and run `& .\stage7-3b3\patch-stage7-qr.ps1`, followed by `& .\stage7-3b3\run-stage7-3b3.ps1`.

The script aborts if it does not find all three exact expected strings; it creates timestamped backups under `stage7-drafts`. This patch was source-reviewed but NOT executed on the user's Windows/JDK 27 environment. Do not commit until the full integration test passes.
