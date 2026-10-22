# Execute from the repository root. This edits only the Stage 7 TEST module POM.
$pom = Join-Path (Get-Location) 'stage7-integration-tests\pom.xml'
if (-not (Test-Path $pom)) { throw "Stage 7 POM not found: $pom" }
$content = [System.IO.File]::ReadAllText($pom)
$pattern = '(?m)^([ \t]*)<!-- Test-only access to the actual Gateway request-signature filter\. -->'
if ($content.Contains('<artifactId>payment-api-service</artifactId>')) {
  Write-Host 'payment-api-service dependency already present'
  return
}
$match = [System.Text.RegularExpressions.Regex]::Match($content, $pattern)
if (-not $match.Success) {
  throw 'Expected dependency marker not found. POM has not been modified.'
}
$indent = $match.Groups[1].Value
$eol = if ($content.Contains("`r`n")) { "`r`n" } else { "`n" }
$addition = @(
  $indent + '<!-- Stage 7.3B3: test-only production MVC Payment API controller. -->'
  $indent + '<dependency>'
  $indent + '  <groupId>com.paymentgateway</groupId>'
  $indent + '  <artifactId>payment-api-service</artifactId>'
  $indent + '  <version>${project.version}</version>'
  $indent + '</dependency>'
) -join $eol
$content = $content.Insert($match.Index, $addition + $eol)
[System.IO.File]::WriteAllText($pom, $content, [System.Text.UTF8Encoding]::new($false))
Write-Host "Added test-only payment-api-service dependency to $pom"
