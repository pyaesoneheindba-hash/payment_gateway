# Stage 7.3B3: patch ONLY disposable multi-JVM test fixtures.
# Never edits production source, Docker, database, Nacos or bank configuration.
# Requires Windows IPv6 loopback. Fails closed; no wildcard / LAN fallback.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# Verify that IPv6 loopback is available before changing any source file.
$listener = $null
try {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::IPv6Loopback, 0)
    $listener.Start()
    Write-Host ('IPv6 loopback preflight: OK, ephemeral port ' + $listener.LocalEndpoint.Port)
} catch {
    throw 'IPv6 ::1 loopback unavailable. No files modified. Do NOT fall back to wildcard or LAN binding.'
} finally {
    if ($null -ne $listener) { $listener.Stop() }
}

$coreRel = 'payment-core-service/src/test/java/com/paymentgateway/core/transport/Stage7CoreChild.java'
$apiRel = 'payment-api-service/src/test/java/com/paymentgateway/api/transport/Stage7PaymentApiChild.java'
$parentRel = 'stage7-integration-tests/src/test/java/com/paymentgateway/transport/Stage7MultiJvmPaymentFlowTest.java'
$changes = @()

$coreFile = Join-Path $root $coreRel
$apiFile = Join-Path $root $apiRel
$parentFile = Join-Path $root $parentRel
foreach ($item in @($coreFile, $apiFile, $parentFile)) {
    if (-not (Test-Path -LiteralPath $item -PathType Leaf)) { throw "Missing expected test file: $item" }
}

$coreOriginal = [System.IO.File]::ReadAllText($coreFile)
$coreOld = '"dubbo.protocol.host=127.0.0.1",'
$coreNew = '"dubbo.protocol.host=::1",'
if (-not $coreOriginal.Contains($coreOld)) { throw "Expected unpatched Core binding configuration not found: $coreFile" }
$coreEdited = $coreOriginal.Replace($coreOld, $coreNew)
$changes += [pscustomobject]@{ Path=$coreFile; Original=$coreOriginal; Edited=$coreEdited }

$apiOriginal = [System.IO.File]::ReadAllText($apiFile)
$apiOld = '"tri://127.0.0.1:" + corePort;'
$apiNew = '"tri://[::1]:" + corePort;'
if (-not $apiOriginal.Contains($apiOld)) { throw "Expected unpatched Payment API RPC URL not found: $apiFile" }
$apiEdited = $apiOriginal.Replace($apiOld, $apiNew)
$changes += [pscustomobject]@{ Path=$apiFile; Original=$apiOriginal; Edited=$apiEdited }

$parentOriginal = [System.IO.File]::ReadAllText($parentFile)
$envOverridePattern = '(?m)^[ \t]*if \(name\.equals\("core"\)\) \{\r?\n[ \t]*env\.put\("DUBBO_IP_TO_BIND", "127\.0\.0\.1"\);\r?\n[ \t]*env\.put\("DUBBO_IP_TO_REGISTRY", "127\.0\.0\.1"\);\r?\n[ \t]*\}\r?\n'
$envMatches = [regex]::Matches($parentOriginal, $envOverridePattern)
if ($envMatches.Count -ne 1) { throw "Expected exactly one old Core DUBBO_IP_TO_BIND/REGISTRY override; found $($envMatches.Count). No files modified." }
$parentEdited = [regex]::Replace($parentOriginal, $envOverridePattern, '')
$changes += [pscustomobject]@{ Path=$parentFile; Original=$parentOriginal; Edited=$parentEdited }

$backup = Join-Path $root ('stage7-drafts/ipv6-loopback-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$encoding = [System.Text.UTF8Encoding]::new($false)
foreach ($change in $changes) {
    $fileName = [System.IO.Path]::GetFileName($change.Path)
    Copy-Item -LiteralPath $change.Path -Destination (Join-Path $backup $fileName) -ErrorAction Stop
    [System.IO.File]::WriteAllText($change.Path, $change.Edited, $encoding)
    Write-Host "Patched TEST fixture: $fileName"
}
Write-Host "Backup of three original TEST files: $backup"
Write-Host 'Core bind address: ::1 (IPv6 loopback only)'
Write-Host 'Payment API Triple URL: tri://[::1]:<selected test port>'
Write-Host 'Legacy DUBBO_IP_TO_BIND and DUBBO_IP_TO_REGISTRY env overrides: removed'
Write-Host 'No Dubbo Triple REST feature was disabled. No production code modified.'
Write-Host 'Next: & .\stage7-3b3\run-stage7-3b3.ps1'
