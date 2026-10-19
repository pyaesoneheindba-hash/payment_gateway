param(
    [ValidateSet('up','down','status','logs','rebuild','verify')]
    [string]$Action = 'up',
    [string]$Service = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

function Get-ComposeEnvFile {
    if (Test-Path '.env') { return '.env' }
    return '.env.example'
}

function New-HexSecret([int]$Bytes = 24) {
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return -join ($buffer | ForEach-Object { $_.ToString('x2') })
}

function Ensure-EnvSecret([string]$Key) {
    $path = Join-Path $root '.env'
    $lines = @(Get-Content $path)
    $pattern = '^' + [regex]::Escape($Key) + '=(.*)$'
    $existing = $null
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) {
            $index = $i
            $existing = $Matches[1].Trim()
            break
        }
    }
    if ($existing) { return $existing }
    $value = New-HexSecret
    if ($index -ge 0) { $lines[$index] = "$Key=$value" }
    else { $lines += "$Key=$value" }
    Set-Content -Path $path -Value $lines -Encoding utf8
    return $value
}

function Get-EnvValue([string]$Key) {
    if (-not (Test-Path '.env')) { return $null }
    $pattern = '^' + [regex]::Escape($Key) + '=(.*)$'
    foreach ($line in Get-Content '.env') {
        if ($line -match $pattern) { return $Matches[1].Trim() }
    }
    return $null
}


function Set-EnvValue([string]$Key, [string]$Value) {
    $path = Join-Path $root '.env'
    $lines = @(Get-Content $path)
    $pattern = '^' + [regex]::Escape($Key) + '=.*$'
    $updated = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) {
            $lines[$i] = "$Key=$Value"
            $updated = $true
            break
        }
    }
    if (-not $updated) { $lines += "$Key=$Value" }
    Set-Content -Path $path -Value $lines -Encoding utf8
}

function Test-DockerVolumeExists([string]$VolumeName) {
    # Do not use `docker volume inspect` as an existence probe here. With
    # $ErrorActionPreference='Stop', Windows PowerShell can promote Docker's
    # expected "no such volume" stderr into a terminating NativeCommandError
    # before we get a chance to inspect $LASTEXITCODE. `docker volume ls` is
    # non-erroring when a named volume does not exist.
    $names = @(docker volume ls --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not list Docker volumes. Verify Docker Desktop is running.'
    }
    return $names -contains $VolumeName
}

function Invoke-BestEffort([scriptblock]$Command) {
    # Cleanup must never hide the original migration error merely because a
    # resource is already absent. This is especially important on Windows
    # PowerShell with ErrorActionPreference=Stop and native stderr.
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'SilentlyContinue'
        & $Command *> $null
    } catch {
        # Intentionally ignored: this helper is for cleanup/recovery only.
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Ensure-KeycloakDataVolume {
    $volume = 'payment-gateway-dev-keycloak-data'
    if (Test-DockerVolumeExists $volume) { return }

    $oldCid = (docker compose --env-file (Get-ComposeEnvFile) ps -aq keycloak 2>$null | Select-Object -First 1)
    if (-not $oldCid) {
        docker volume create $volume | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create persistent Keycloak data volume.' }
        Write-Host 'Created persistent Keycloak data volume.' -ForegroundColor Green
        return
    }

    if (-not $script:EnvExistedBeforeRun) {
        docker volume create $volume | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create persistent Keycloak data volume.' }
        Write-Host 'A new .env was created, so the old ephemeral Keycloak database will not be migrated because its client secrets may not match. Existing merchant database records remain safe; use Admin Portal -> Repair login for affected merchant users.' -ForegroundColor Yellow
        return
    }

    # Earlier generated stacks stored Keycloak's H2 database only in the
    # container writable layer. Stop it for a consistent snapshot, commit that
    # layer temporarily, and copy /opt/keycloak/data into the new named volume.
    $wasRunning = (docker inspect --format '{{.State.Running}}' $oldCid 2>$null) -eq 'true'
    $migrationImage = 'payment-gateway-keycloak-migration:' + ([guid]::NewGuid().ToString('N'))
    Write-Host 'Migrating existing Keycloak users into persistent storage (one time)...' -ForegroundColor Yellow
    try {
        if ($wasRunning) {
            docker stop $oldCid | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not stop existing Keycloak container for migration.' }
        }
        docker commit $oldCid $migrationImage | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not snapshot existing Keycloak container.' }
        docker volume create $volume | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create persistent Keycloak data volume.' }
        docker run --rm --user 0 --entrypoint /bin/sh `
          --mount "type=volume,source=$volume,target=/dest" `
          $migrationImage -ec 'cp -a /opt/keycloak/data/. /dest/; find /dest -type f \( -name "*.lock.db" -o -name "*.trace.db" \) -delete 2>/dev/null || true'
        if ($LASTEXITCODE -ne 0) { throw 'Could not copy existing Keycloak data into the persistent volume.' }
        Write-Host '[PASS] Existing Keycloak identities migrated to persistent storage.' -ForegroundColor Green
    }
    catch {
        Invoke-BestEffort { docker volume rm -f $volume }
        if ($wasRunning) { Invoke-BestEffort { docker start $oldCid } }
        throw
    }
    finally {
        Invoke-BestEffort { docker image rm -f $migrationImage }
    }
}



function Show-ServiceDiagnostics([string]$Service) {
    $cid = (docker compose --env-file (Get-ComposeEnvFile) ps -aq $Service 2>$null | Select-Object -First 1)
    if ($cid) {
        Write-Host "Container diagnostics for $Service" -ForegroundColor DarkYellow
        docker inspect --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}} exit={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} restarts={{.RestartCount}} error={{.State.Error}}' $cid 2>$null
    }
    docker compose --env-file (Get-ComposeEnvFile) logs --tail 400 $Service
    if ($Service -eq 'rmqbroker' -and $cid) {
        Write-Host 'RocketMQ broker.log tail (when available):' -ForegroundColor DarkYellow
        docker exec $cid sh -lc 'for f in /home/rocketmq/logs/rocketmqlogs/broker.log /home/rocketmq/logs/rocketmqlogs/proxy.log; do if [ -f "$f" ]; then echo "===== $f ====="; tail -n 200 "$f"; fi; done' 2>$null
    }
}

function Assert-ComposeConfig {
    Write-Host 'Validating Docker Compose configuration...' -ForegroundColor Cyan
    docker compose --env-file (Get-ComposeEnvFile) config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose configuration validation failed.' }
    Write-Host '[PASS] Docker Compose configuration' -ForegroundColor Green
}

function Wait-ServiceHealth([string]$Service, [int]$TimeoutSeconds = 300) {
    $started = Get-Date
    Write-Host -NoNewline "Waiting for $Service to become healthy"
    while ($true) {
        $cid = (docker compose --env-file (Get-ComposeEnvFile) ps -q $Service 2>$null | Select-Object -First 1)
        if ($cid) {
            $status = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $cid 2>$null
            if ($status -in @('healthy','running')) {
                Write-Host " -> $status" -ForegroundColor Green
                return
            }
            if ($status -in @('unhealthy','exited','dead')) {
                Write-Host " -> $status" -ForegroundColor Red
                Show-ServiceDiagnostics $Service
                throw "$Service failed readiness check ($status)."
            }
        }
        if (((Get-Date) - $started).TotalSeconds -ge $TimeoutSeconds) {
            Write-Host ' -> timed out' -ForegroundColor Red
            docker compose --env-file (Get-ComposeEnvFile) ps $Service
            Show-ServiceDiagnostics $Service
            throw "$Service did not become healthy within $TimeoutSeconds seconds."
        }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 2
    }
}

function Wait-CriticalStack {
    foreach ($service in @('nacos','keycloak','rmqnamesrv','rmqbroker','payment-core-service','payment-api-service','callback-service','api-gateway','notification-service')) {
        Wait-ServiceHealth $service 300
    }
}

function Assert-OneShotCompleted([string]$Service) {
    $cid = (docker compose --env-file (Get-ComposeEnvFile) ps -aq $Service 2>$null | Select-Object -First 1)
    if (-not $cid) { throw "$Service container was not created." }
    $status = docker inspect --format '{{.State.Status}}' $cid 2>$null
    $exitCode = docker inspect --format '{{.State.ExitCode}}' $cid 2>$null
    if ($status -ne 'exited' -or $exitCode -ne '0') {
        Show-ServiceDiagnostics $Service
        throw "$Service did not complete successfully (status=$status exit=$exitCode)."
    }
    Write-Host "[PASS] $Service completed successfully" -ForegroundColor Green
}

function Assert-HttpOk([string]$Name, [string]$Url) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 -Uri $Url
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) { throw "HTTP $($response.StatusCode)" }
        Write-Host "[PASS] $Name" -ForegroundColor Green
    } catch {
        throw "$Name failed: $($_.Exception.Message)"
    }
}

function Get-KeycloakClientToken([string]$Realm, [string]$ClientId, [string]$ClientSecret) {
    $tokenResponse = Invoke-RestMethod -TimeoutSec 20 `
      -Method POST `
      -Uri "http://localhost:8180/realms/$Realm/protocol/openid-connect/token" `
      -ContentType 'application/x-www-form-urlencoded' `
      -Body @{
        grant_type='client_credentials'
        client_id=$ClientId
        client_secret=$ClientSecret
      }
    if (-not $tokenResponse.access_token) { throw "Keycloak did not return an access token for client '$ClientId'." }
    return [string]$tokenResponse.access_token
}

function Repair-KeycloakDevCredentials {
    # A persistent Keycloak volume can outlive an extracted project directory.
    # If a fresh .env is generated, its random client/admin secrets no longer
    # match the identities stored in that volume. Recover access without deleting
    # merchant identities by creating a short-lived offline bootstrap admin
    # service account, then rotate only the local provisioning secret and reset
    # the local pgadmin password to the current .env values.
    $volume = 'payment-gateway-dev-keycloak-data'
    if (-not (Test-DockerVolumeExists $volume)) {
        throw 'Cannot repair Keycloak credentials because the persistent Keycloak volume does not exist.'
    }

    $recoveryClient = 'txn-hub-recovery-' + ([guid]::NewGuid().ToString('N').Substring(0,12))
    $recoverySecret = New-HexSecret 32
    $portalPassword = Get-EnvValue 'DEV_PORTAL_ADMIN_PASSWORD'
    if ([string]::IsNullOrWhiteSpace($portalPassword)) { throw 'DEV_PORTAL_ADMIN_PASSWORD is missing.' }

    Write-Host 'Keycloak provisioning credentials do not match the persistent realm. Repairing local development credentials without deleting users...' -ForegroundColor Yellow

    docker compose --env-file (Get-ComposeEnvFile) stop keycloak | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop Keycloak for offline credential recovery.' }

    try {
        docker run --rm `
          --env "TXN_HUB_RECOVERY_SECRET=$recoverySecret" `
          --mount "type=volume,source=$volume,target=/opt/keycloak/data" `
          --entrypoint /opt/keycloak/bin/kc.sh `
          quay.io/keycloak/keycloak:26.8.0 `
          bootstrap-admin service `
          --client-id $recoveryClient `
          --client-secret:env=TXN_HUB_RECOVERY_SECRET `
          --no-prompt | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the temporary Keycloak recovery service account.' }
    }
    catch {
        Invoke-BestEffort { docker compose --env-file (Get-ComposeEnvFile) start keycloak }
        throw
    }

    docker compose --env-file (Get-ComposeEnvFile) start keycloak | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restart Keycloak after credential recovery bootstrap.' }
    Wait-ServiceHealth 'keycloak' 180

    $recoveryToken = Get-KeycloakClientToken 'master' $recoveryClient $recoverySecret
    $headers = @{ Authorization = "Bearer $recoveryToken" }

    try {
        $clients = @(Invoke-RestMethod -TimeoutSec 20 -Headers $headers `
          -Uri 'http://localhost:8180/admin/realms/payment-gateway/clients?clientId=payment-gateway-user-admin&exact=true')
        if ($clients.Count -ne 1 -or -not $clients[0].id) {
            throw 'Could not uniquely locate payment-gateway-user-admin in Keycloak.'
        }

        $clientUuid = [string]$clients[0].id
        $credential = Invoke-RestMethod -TimeoutSec 20 -Method POST -Headers $headers `
          -Uri "http://localhost:8180/admin/realms/payment-gateway/clients/$clientUuid/client-secret"
        $newProvisionerSecret = [string]$credential.value
        if ([string]::IsNullOrWhiteSpace($newProvisionerSecret)) {
            throw 'Keycloak rotated the provisioning client secret but did not return the new value.'
        }
        Set-EnvValue 'DEV_KEYCLOAK_USER_ADMIN_CLIENT_SECRET' $newProvisionerSecret

        $admins = @(Invoke-RestMethod -TimeoutSec 20 -Headers $headers `
          -Uri 'http://localhost:8180/admin/realms/payment-gateway/users?username=pgadmin&exact=true')
        if ($admins.Count -eq 1 -and $admins[0].id) {
            $passwordJson = @{
                type='password'
                value=$portalPassword
                temporary=$false
            } | ConvertTo-Json -Compress
            Invoke-RestMethod -TimeoutSec 20 -Method PUT -Headers $headers `
              -ContentType 'application/json' -Body $passwordJson `
              -Uri "http://localhost:8180/admin/realms/payment-gateway/users/$($admins[0].id)/reset-password" | Out-Null
        }

        Write-Host '[PASS] Keycloak local development credentials reconciled with the persistent realm.' -ForegroundColor Green
    }
    finally {
        try {
            $masterClients = @(Invoke-RestMethod -TimeoutSec 20 -Headers $headers `
              -Uri "http://localhost:8180/admin/realms/master/clients?clientId=$recoveryClient&exact=true")
            if ($masterClients.Count -eq 1 -and $masterClients[0].id) {
                Invoke-RestMethod -TimeoutSec 20 -Method DELETE -Headers $headers `
                  -Uri "http://localhost:8180/admin/realms/master/clients/$($masterClients[0].id)" | Out-Null
            }
        }
        catch {
            Write-Host 'Warning: temporary Keycloak recovery client cleanup did not complete automatically.' -ForegroundColor Yellow
        }
    }

    # payment-api-service receives the provisioning client secret through its
    # container environment. Recreate only this service so it picks up the
    # rotated .env value; merchant/PostgreSQL/Keycloak data are untouched.
    docker compose --env-file (Get-ComposeEnvFile) up -d --force-recreate payment-api-service | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not recreate payment-api-service after Keycloak secret rotation.' }
    Wait-ServiceHealth 'payment-api-service' 180
}

function Assert-KeycloakProvisioner {
    $secret = Get-EnvValue 'DEV_KEYCLOAK_USER_ADMIN_CLIENT_SECRET'
    if ([string]::IsNullOrWhiteSpace($secret)) { throw 'DEV_KEYCLOAK_USER_ADMIN_CLIENT_SECRET is missing.' }

    try {
        [void](Get-KeycloakClientToken 'payment-gateway' 'payment-gateway-user-admin' $secret)
        Write-Host '[PASS] Keycloak merchant-user provisioning client' -ForegroundColor Green
        return
    }
    catch {
        Write-Host "Initial Keycloak provisioning-client check failed: $($_.Exception.Message)" -ForegroundColor Yellow
    }

    Repair-KeycloakDevCredentials

    $secret = Get-EnvValue 'DEV_KEYCLOAK_USER_ADMIN_CLIENT_SECRET'
    try {
        [void](Get-KeycloakClientToken 'payment-gateway' 'payment-gateway-user-admin' $secret)
        Write-Host '[PASS] Keycloak merchant-user provisioning client (after automatic repair)' -ForegroundColor Green
    }
    catch {
        throw "Keycloak merchant-user provisioning client is still unusable after automatic repair: $($_.Exception.Message)"
    }
}

function Assert-WebhookSigningSecret {
    $keyName = 'payment-gateway-webhook-dev-01-private.pem'
    $hostKey = Join-Path (Join-Path $root '.runtime-secrets') $keyName
    if (-not (Test-Path $hostKey)) {
        throw "Webhook signing private key is missing on host: $hostKey"
    }

    docker compose --env-file (Get-ComposeEnvFile) exec -T notification-service sh -c "test -r /run/payment-gateway/secrets/$keyName"
    if ($LASTEXITCODE -ne 0) {
        throw "notification-service cannot read /run/payment-gateway/secrets/$keyName. Check the .runtime-secrets bind mount."
    }
    Write-Host '[PASS] Notification webhook signing key mount' -ForegroundColor Green
}

function Verify-Stack {
    Write-Host ''
    Write-Host 'TXN HUB local stack verification' -ForegroundColor Cyan
    Wait-CriticalStack
    Assert-WebhookSigningSecret
    Assert-OneShotCompleted 'rocketmq-storage-init'
    Assert-OneShotCompleted 'rocketmq-init'
    Assert-HttpOk 'Payment API readiness' 'http://127.0.0.1:8087/actuator/health/readiness'
    Assert-HttpOk 'API Gateway readiness' 'http://127.0.0.1:8086/actuator/health/readiness'
    Assert-HttpOk 'Admin Portal' 'http://127.0.0.1:4202/'
    Assert-HttpOk 'Merchant Portal' 'http://127.0.0.1:4201/'
    Assert-KeycloakProvisioner
    Write-Host '[PASS] STACK READY' -ForegroundColor Green
}

function Ensure-LocalConfig {
    $script:EnvExistedBeforeRun = Test-Path '.env'
    if (-not $script:EnvExistedBeforeRun) {
        Copy-Item '.env.example' '.env'
        Write-Host 'Created .env from .env.example. Provider credentials are optional until you use that provider.' -ForegroundColor Yellow
    }

    [void](Ensure-EnvSecret 'DEV_KEYCLOAK_ADMIN_PASSWORD')
    [void](Ensure-EnvSecret 'DEV_PORTAL_ADMIN_PASSWORD')
    [void](Ensure-EnvSecret 'DEV_KEYCLOAK_USER_ADMIN_CLIENT_SECRET')

    $secretDir = Join-Path $root '.runtime-secrets'
    New-Item -ItemType Directory -Force $secretDir | Out-Null

    $hmacFile = Join-Path $secretDir 'public-token-hmac.b64'
    if (-not (Test-Path $hmacFile)) {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        [Convert]::ToBase64String($bytes) | Set-Content -NoNewline -Encoding ascii $hmacFile
        Write-Host 'Generated local public-token HMAC secret.' -ForegroundColor Green
    }

    $keyId = 'payment-gateway-webhook-dev-01'
    $privateKey = Join-Path $secretDir "$keyId-private.pem"
    if (-not (Test-Path $privateKey)) {
        Write-Host 'Generating local webhook Ed25519 key pair in .runtime-secrets...' -ForegroundColor Yellow
        docker run --rm `
          --mount "type=bind,source=$root,target=/workspace" `
          --mount "type=bind,source=$secretDir,target=/out" `
          -w /workspace `
          eclipse-temurin:25 `
          java scripts/security/GenerateGatewayWebhookKeyPair.java $keyId /out
        if ($LASTEXITCODE -ne 0) { throw 'Webhook key generation failed.' }
    }
    $publicKey = Join-Path $secretDir "$keyId-public.pem"
    if (-not (Test-Path $privateKey) -or -not (Test-Path $publicKey)) {
        throw "Webhook signing key generation is incomplete. Expected $privateKey and $publicKey"
    }
}

switch ($Action) {
    'up' {
        Ensure-LocalConfig
        Ensure-KeycloakDataVolume
        Assert-ComposeConfig
        docker compose --env-file (Get-ComposeEnvFile) up -d --build
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Verify-Stack
        docker compose --env-file (Get-ComposeEnvFile) ps
        $portalPassword = Get-EnvValue 'DEV_PORTAL_ADMIN_PASSWORD'
        Write-Host ''
        Write-Host 'Gateway:         http://localhost:8086' -ForegroundColor Cyan
        Write-Host 'Checkout:        http://localhost:4200' -ForegroundColor Cyan
        Write-Host 'Merchant portal: http://localhost:4201' -ForegroundColor Cyan
        Write-Host 'Admin portal:    http://localhost:4202' -ForegroundColor Cyan
        Write-Host 'Keycloak:        http://localhost:8180' -ForegroundColor Cyan
        Write-Host 'Nacos:           http://localhost:18848/nacos' -ForegroundColor Cyan
        Write-Host 'PostgreSQL:      localhost:15432 (payment/payment_gateway)' -ForegroundColor Cyan
        Write-Host "Dev admin login: pgadmin / $portalPassword" -ForegroundColor Yellow
        Write-Host 'The generated development password is stored only in ignored .env.' -ForegroundColor DarkYellow
    }
    'rebuild' {
        Ensure-LocalConfig
        Ensure-KeycloakDataVolume
        Assert-ComposeConfig
        docker compose --env-file (Get-ComposeEnvFile) build --no-cache
        docker compose --env-file (Get-ComposeEnvFile) up -d
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Verify-Stack
        docker compose --env-file (Get-ComposeEnvFile) ps
    }
    'down' {
        docker compose --env-file (Get-ComposeEnvFile) down
    }
    'status' {
        docker compose --env-file (Get-ComposeEnvFile) ps
    }
    'verify' {
        Ensure-LocalConfig
        Verify-Stack
    }
    'logs' {
        if ($Service) { docker compose --env-file (Get-ComposeEnvFile) logs -f --tail 200 $Service }
        else { docker compose --env-file (Get-ComposeEnvFile) logs -f --tail 100 }
    }
}
