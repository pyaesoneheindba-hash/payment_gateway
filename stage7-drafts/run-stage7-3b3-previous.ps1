# Stage 7.3B3 cross-process test. Does not touch production Docker Compose,
# Nacos, banks or existing database. Testcontainers creates its own PostgreSQL.
# Execute from repository root or from this script's directory.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
if (-not (Test-Path 'pom.xml')) { throw "No parent pom.xml at $root" }

$files = @(
 'api-gateway/src/test/java/com/paymentgateway/gateway/transport/Stage7GatewayChild.java',
 'payment-api-service/src/test/java/com/paymentgateway/api/transport/Stage7PaymentApiChild.java',
 'payment-core-service/src/test/java/com/paymentgateway/core/transport/Stage7CoreChild.java',
 'stage7-integration-tests/src/test/java/com/paymentgateway/transport/Stage7MultiJvmPaymentFlowTest.java'
)
foreach ($file in $files) {
    if (-not (Test-Path $file)) { throw "Missing Stage 7B3 source file: $file" }
}

Write-Host "Stage 7.3B3: preparing TEST classpaths for independent JVMs"
# A Spring Boot executable JAR places classes under BOOT-INF/classes.
# Maven *consumer* modules require ordinary JARs with classes at the archive root.
# This flag is for the temporary local Maven repository installation ONLY:
# it does not change source code, disable Dubbo/REST/WebFlux, or affect the
# production package build (which uses the default repackage behavior).
Write-Host 'Installing ordinary dependency JARs into LOCAL Maven repository (test-build preparation only).'
& mvn '-pl' 'api-gateway,payment-api-service,payment-core-service,stage7-integration-tests' '-am' 'install' '-DskipTests' '-Dspring-boot.repackage.skip=true'
if ($LASTEXITCODE -ne 0) { throw 'Local build failed; no integration processes started.' }

foreach ($module in @('api-gateway', 'payment-api-service', 'payment-core-service')) {
    $pom = Join-Path (Join-Path $root $module) 'pom.xml'
    $output = Join-Path (Join-Path $root $module) 'target/stage7-3b3.classpath'
    & mvn '-f' $pom 'dependency:build-classpath' "-Dmdep.outputFile=$output" '-DincludeScope=test'
    if ($LASTEXITCODE -ne 0) { throw "Failed to build test classpath for $module" }
    if (-not (Test-Path $output)) { throw "Expected classpath file missing: $output" }
    if ((Get-Item $output).Length -eq 0) { throw "Empty test classpath: $output" }
}

Write-Host 'Launching separate Gateway, Payment API and Payment Core JVMs with mock bank + disposable PostgreSQL.'
Write-Host 'No JVM option disables Dubbo Triple REST mapping.'
& mvn '-pl' 'stage7-integration-tests' '-am' 'test' '-Dtest=Stage7MultiJvmPaymentFlowTest' '-Dsurefire.failIfNoSpecifiedTests=false'
$code = $LASTEXITCODE
Write-Host "Stage 7.3B3 exit code: $code"
Write-Host 'Child process diagnostics: stage7-integration-tests/target/stage7-3b3-logs/'
if ($code -ne 0) { throw 'Stage 7.3B3 integration test failed: see surefire reports and child logs.' }
