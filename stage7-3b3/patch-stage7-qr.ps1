$ErrorActionPreference = 'Stop'
$repo = (Get-Location).Path
if (-not (Test-Path (Join-Path $repo 'pom.xml'))) {
    throw 'Run this patch from the payment gateway repository root (the folder containing pom.xml).'
}
$corePath = Join-Path $repo 'payment-core-service\src\test\java\com\paymentgateway\core\transport\Stage7CoreChild.java'
$testPath = Join-Path $repo 'stage7-integration-tests\src\test\java\com\paymentgateway\transport\Stage7MultiJvmPaymentFlowTest.java'
if (-not (Test-Path $corePath) -or -not (Test-Path $testPath)) {
    throw 'Expected Stage 7.3B3 test-only files were not found. No files changed.'
}
$core = [System.IO.File]::ReadAllText($corePath)
$test = [System.IO.File]::ReadAllText($testPath)
$oldCore = 'new InitiatePaymentResult(ProviderOutcome.PENDING, PROVIDER,'
$newCore = 'new InitiatePaymentResult(ProviderOutcome.SUCCESS, PROVIDER,'
$oldStatus = 'assertTrue(response.body().contains("\"status\":\"PENDING\""), response.body());'
$newStatus = 'assertTrue(response.body().contains("\"status\":\"QR_GENERATED\""), response.body());'
$oldQR = 'assertTrue(response.body().contains("LOCAL-QR-" + order), response.body());'
$newQR = 'assertTrue(response.body().contains("\"qr\":\"LOCAL-QR-" + order + "\""), response.body());'
if ($core.Split(@($oldCore), [System.StringSplitOptions]::None).Count -ne 2 -or
    $test.Split(@($oldStatus), [System.StringSplitOptions]::None).Count -ne 2 -or
    $test.Split(@($oldQR), [System.StringSplitOptions]::None).Count -ne 2) {
    throw 'The Java source differs from the reviewed version. Nothing was changed. Do not apply this patch blindly.'
}
$backup = Join-Path $repo ('stage7-drafts\qr-fixture-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $backup | Out-Null
Copy-Item $corePath (Join-Path $backup 'Stage7CoreChild.java')
Copy-Item $testPath (Join-Path $backup 'Stage7MultiJvmPaymentFlowTest.java')
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($corePath, $core.Replace($oldCore, $newCore), $utf8)
[System.IO.File]::WriteAllText($testPath, $test.Replace($oldStatus, $newStatus).Replace($oldQR, $newQR), $utf8)
Write-Host 'Patched test-only mock outcome and response assertions; backups:' $backup
Write-Host 'No production Java, pom.xml, or Docker resource was changed.'
