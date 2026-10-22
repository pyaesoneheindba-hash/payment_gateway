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
Write-Host 'CLEAN build of ordinary dependency JARs for isolated Stage 7B3 tests only.'
Write-Host 'This removes Maven target/ build outputs in selected reactor modules.'
Write-Host 'Production Java/POM/config files, Docker services and bank endpoints are unchanged.'
# CLEAN is REQUIRED here: a prior 'mvn package' created executable Boot
# JARs in target/. Without clean, incremental maven-jar-plugin may reuse
# those artifacts even while spring-boot:repackage is skipped.
& mvn '-pl' 'api-gateway,payment-api-service,payment-core-service,stage7-integration-tests' '-am' 'clean' 'install' '-DskipTests' '-Dspring-boot.repackage.skip=true'
if ($LASTEXITCODE -ne 0) { throw 'Clean local dependency build failed; no integration processes started.' }

# Fail early if any reactor artifact is still a Spring Boot executable jar.
# Ordinary compile dependencies must have classes at jar root, not BOOT-INF/classes.
$jarCmd = 'jar'
if ($env:JAVA_HOME) {
    $candidateJarCmd = Join-Path $env:JAVA_HOME 'bin/jar.exe'
    if (Test-Path $candidateJarCmd) { $jarCmd = $candidateJarCmd }
}
$expectedClasses = [ordered]@{
    'api-gateway' = 'com/paymentgateway/gateway/security/ClientRequestCrypto.class'
    'payment-core-service' = 'com/paymentgateway/core/service/PaymentRpcServiceImpl.class'
    'notification-service' = 'com/paymentgateway/notification/RocketMqClientManager.class'
}
foreach ($module in $expectedClasses.Keys) {
    $jarPath = Join-Path $root ("$module/target/$module-1.0.0-SNAPSHOT.jar")
    if (-not (Test-Path $jarPath)) { throw "Expected ordinary JAR not found: $jarPath" }
    $contents = @( & $jarCmd 'tf' $jarPath )
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect JAR: $jarPath" }
    $required = $expectedClasses[$module]
    if ($contents -notcontains $required) {
        if ($contents -contains "BOOT-INF/classes/$required") {
            throw "BAD TEST DEPENDENCY: $module is still an executable Spring Boot JAR. A clean normal JAR is required."
        }
        throw "BAD TEST DEPENDENCY: required class $required is missing from $jarPath"
    }
    Write-Host "Verified ordinary test-dependency JAR: $module"
}

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
