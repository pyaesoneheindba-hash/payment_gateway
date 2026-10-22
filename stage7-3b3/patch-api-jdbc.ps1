# Fix the Stage 7.3B3 *test fixture* only. No production source changes.
# QR_STRING in this slice reaches Core via real Dubbo RPC and does not query API JDBC.
# The checkout JDBC used in HOSTED_PAGE will be covered in a separate test phase.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$relative = 'payment-api-service/src/test/java/com/paymentgateway/api/transport/Stage7PaymentApiChild.java'
$source = Join-Path $root $relative
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw "Missing expected TEST fixture: $source"
}
$original = [System.IO.File]::ReadAllText($source)
$old = '@Bean JdbcTemplate isolatedJdbc() { return new JdbcTemplate(); }'
$new = @'
@Bean JdbcTemplate isolatedJdbc() {
            // QR_STRING does not use CheckoutSessionService JDBC.
            // Use an inert DataSource solely to satisfy JdbcTemplate initialization.
            // No database connection can be opened by this mocked DataSource.
            return new JdbcTemplate(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        }
'@
if ($original.Contains($new.TrimEnd())) {
    Write-Host 'Stage 7.3B3 JDBC fixture is already patched; no changes made.'
    exit 0
}
$matches = [regex]::Matches($original, [regex]::Escape($old)).Count
if ($matches -ne 1) {
    throw "Expected exactly one unpatched isolatedJdbc() method. Found $matches. No files modified."
}
$edited = $original.Replace($old,$new.TrimEnd())
$backupDirectory = Join-Path $root 'stage7-drafts'
if (-not (Test-Path -LiteralPath $backupDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
}
$backup = Join-Path $backupDirectory ('Stage7PaymentApiChild-before-jdbc-fix-' + [guid]::NewGuid().ToString('N') + '.java')
Copy-Item -LiteralPath $source -Destination $backup -ErrorAction Stop
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($source,$edited,$utf8)
Write-Host "Patched TEST-only API JdbcTemplate bean: $source"
Write-Host "Original backed up: $backup"
Write-Host 'No production Java, POM, Docker or database configuration changed.'
Write-Host 'Next: & .\stage7-3b3\run-stage7-3b3.ps1'
