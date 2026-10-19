#!/usr/bin/env sh
set -eu
ACTION="${1:-up}"
SERVICE="${2:-}"
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

compose_env_file() {
  if [ -f .env ]; then printf '%s' .env; else printf '%s' .env.example; fi
}

new_hex_secret() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

ensure_env_secret() {
  key="$1"
  current=$(sed -n "s/^${key}=//p" .env | tail -n 1)
  if [ -n "$current" ]; then printf '%s' "$current"; return; fi
  value=$(new_hex_secret)
  if grep -q "^${key}=" .env; then
    sed -i.bak "s/^${key}=.*/${key}=${value}/" .env && rm -f .env.bak
  else
    printf '\n%s=%s\n' "$key" "$value" >> .env
  fi
  printf '%s' "$value"
}

get_env_value() {
  sed -n "s/^$1=//p" .env | tail -n 1
}

ensure_keycloak_data_volume() {
  volume='payment-gateway-dev-keycloak-data'
  if docker volume inspect "$volume" >/dev/null 2>&1; then return 0; fi

  old_cid=$(docker compose --env-file "$(compose_env_file)" ps -aq keycloak 2>/dev/null | head -n 1)
  if [ -z "$old_cid" ]; then
    docker volume create "$volume" >/dev/null
    echo 'Created persistent Keycloak data volume.'
    return 0
  fi

  if [ "${ENV_EXISTED_BEFORE_RUN:-false}" != true ]; then
    docker volume create "$volume" >/dev/null
    echo 'A new .env was created, so the old ephemeral Keycloak database will not be migrated because its client secrets may not match. Existing merchant database records remain safe; use Admin Portal -> Repair login for affected merchant users.'
    return 0
  fi

  was_running=$(docker inspect --format '{{.State.Running}}' "$old_cid" 2>/dev/null || true)
  migration_image="payment-gateway-keycloak-migration:$(date +%s)-$$"
  echo 'Migrating existing Keycloak users into persistent storage (one time)...'
  if [ "$was_running" = true ]; then docker stop "$old_cid" >/dev/null; fi
  if ! docker commit "$old_cid" "$migration_image" >/dev/null; then
    [ "$was_running" = true ] && docker start "$old_cid" >/dev/null || true
    return 1
  fi
  docker volume create "$volume" >/dev/null
  if ! docker run --rm --user 0 --entrypoint /bin/sh \
      --mount "type=volume,source=$volume,target=/dest" \
      "$migration_image" -ec 'cp -a /opt/keycloak/data/. /dest/; find /dest -type f \( -name "*.lock.db" -o -name "*.trace.db" \) -delete 2>/dev/null || true'; then
    docker volume rm -f "$volume" >/dev/null 2>&1 || true
    docker image rm -f "$migration_image" >/dev/null 2>&1 || true
    [ "$was_running" = true ] && docker start "$old_cid" >/dev/null || true
    return 1
  fi
  docker image rm -f "$migration_image" >/dev/null 2>&1 || true
  echo '[PASS] Existing Keycloak identities migrated to persistent storage.'
}


show_service_diagnostics() {
  service="$1"
  cid=$(docker compose --env-file "$(compose_env_file)" ps -aq "$service" | head -n 1)
  if [ -n "$cid" ]; then
    echo "Container diagnostics for $service"
    docker inspect --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}} exit={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} restarts={{.RestartCount}} error={{.State.Error}}' "$cid" 2>/dev/null || true
  fi
  docker compose --env-file "$(compose_env_file)" logs --tail 400 "$service" || true
  if [ "$service" = rmqbroker ] && [ -n "$cid" ]; then
    echo 'RocketMQ broker.log tail (when available):'
    docker exec "$cid" sh -lc 'for f in /home/rocketmq/logs/rocketmqlogs/broker.log /home/rocketmq/logs/rocketmqlogs/proxy.log; do if [ -f "$f" ]; then echo "===== $f ====="; tail -n 200 "$f"; fi; done' 2>/dev/null || true
  fi
}

assert_compose_config() {
  echo 'Validating Docker Compose configuration...'
  docker compose --env-file "$(compose_env_file)" config --quiet
  echo '[PASS] Docker Compose configuration'
}

wait_for_service_health() {
  service="$1"
  timeout_seconds="${2:-300}"
  start=$(date +%s)
  printf 'Waiting for %s to become healthy' "$service"
  while :; do
    cid=$(docker compose --env-file "$(compose_env_file)" ps -q "$service")
    if [ -n "$cid" ]; then
      status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)
      case "$status" in
        healthy|running) printf ' -> %s\n' "$status"; return 0 ;;
        unhealthy|exited|dead)
          printf ' -> %s\n' "$status"
          show_service_diagnostics "$service"
          return 1
          ;;
      esac
    fi
    now=$(date +%s)
    if [ $((now-start)) -ge "$timeout_seconds" ]; then
      printf ' -> timed out\n'
      docker compose --env-file "$(compose_env_file)" ps "$service" || true
      show_service_diagnostics "$service"
      return 1
    fi
    printf '.'
    sleep 2
  done
}

wait_for_critical_stack() {
  for service in nacos keycloak rmqnamesrv rmqbroker payment-core-service payment-api-service callback-service api-gateway notification-service; do
    wait_for_service_health "$service" 300
  done
}

assert_one_shot_completed() {
  service="$1"
  cid=$(docker compose --env-file "$(compose_env_file)" ps -aq "$service" | head -n 1)
  [ -n "$cid" ] || { echo "$service container was not created" >&2; return 1; }
  status=$(docker inspect --format '{{.State.Status}}' "$cid")
  exit_code=$(docker inspect --format '{{.State.ExitCode}}' "$cid")
  if [ "$status" != exited ] || [ "$exit_code" != 0 ]; then
    show_service_diagnostics "$service"
    echo "$service did not complete successfully (status=$status exit=$exit_code)" >&2
    return 1
  fi
  printf '[PASS] %s completed successfully\n' "$service"
}

assert_http_ok() {
  name="$1"
  url="$2"
  if curl -fsS --max-time 15 "$url" >/dev/null; then
    printf '[PASS] %s\n' "$name"
  else
    printf '[FAIL] %s\n' "$name" >&2
    return 1
  fi
}

assert_keycloak_provisioner() {
  secret=$(get_env_value DEV_KEYCLOAK_USER_ADMIN_CLIENT_SECRET)
  [ -n "$secret" ] || { echo 'DEV_KEYCLOAK_USER_ADMIN_CLIENT_SECRET is missing' >&2; return 1; }
  if curl -fsS --max-time 15 \
      -X POST 'http://localhost:8180/realms/payment-gateway/protocol/openid-connect/token' \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode 'grant_type=client_credentials' \
      --data-urlencode 'client_id=payment-gateway-user-admin' \
      --data-urlencode "client_secret=$secret" >/dev/null; then
    printf '[PASS] Keycloak merchant-user provisioning client\n'
  else
    echo '[FAIL] Keycloak merchant-user provisioning client' >&2
    return 1
  fi
}

assert_webhook_signing_secret() {
  key_name=payment-gateway-webhook-dev-01-private.pem
  [ -f "$ROOT/.runtime-secrets/$key_name" ] || { echo "Webhook signing private key is missing on host: $ROOT/.runtime-secrets/$key_name" >&2; return 1; }
  docker compose --env-file "$(compose_env_file)" exec -T notification-service sh -c "test -r /run/payment-gateway/secrets/$key_name" || {
    echo "notification-service cannot read /run/payment-gateway/secrets/$key_name. Check the .runtime-secrets bind mount." >&2
    return 1
  }
  printf '[PASS] Notification webhook signing key mount\n'
}

verify_stack() {
  printf '\nTXN HUB local stack verification\n'
  wait_for_critical_stack
  assert_webhook_signing_secret
  assert_one_shot_completed rocketmq-storage-init
  assert_one_shot_completed rocketmq-init
  assert_http_ok 'Payment API readiness' 'http://127.0.0.1:8087/actuator/health/readiness'
  assert_http_ok 'API Gateway readiness' 'http://127.0.0.1:8086/actuator/health/readiness'
  assert_http_ok 'Admin Portal' 'http://127.0.0.1:4202/'
  assert_http_ok 'Merchant Portal' 'http://127.0.0.1:4201/'
  assert_keycloak_provisioner
  printf '[PASS] STACK READY\n'
}

ensure_config() {
  if [ -f .env ]; then ENV_EXISTED_BEFORE_RUN=true; else ENV_EXISTED_BEFORE_RUN=false; cp .env.example .env; fi
  ensure_env_secret DEV_KEYCLOAK_ADMIN_PASSWORD >/dev/null
  ensure_env_secret DEV_PORTAL_ADMIN_PASSWORD >/dev/null
  ensure_env_secret DEV_KEYCLOAK_USER_ADMIN_CLIENT_SECRET >/dev/null
  mkdir -p .runtime-secrets
  if [ ! -f .runtime-secrets/public-token-hmac.b64 ]; then
    head -c 32 /dev/urandom | base64 | tr -d '\n' > .runtime-secrets/public-token-hmac.b64
  fi
  KEY_ID=payment-gateway-webhook-dev-01
  if [ ! -f ".runtime-secrets/${KEY_ID}-private.pem" ]; then
    docker run --rm \
      --mount "type=bind,source=$ROOT,target=/workspace" \
      --mount "type=bind,source=$ROOT/.runtime-secrets,target=/out" \
      -w /workspace eclipse-temurin:25 \
      java scripts/security/GenerateGatewayWebhookKeyPair.java "$KEY_ID" /out
  fi
  [ -f ".runtime-secrets/${KEY_ID}-private.pem" ] && [ -f ".runtime-secrets/${KEY_ID}-public.pem" ] || {
    echo "Webhook signing key generation is incomplete for $KEY_ID" >&2
    return 1
  }
}

case "$ACTION" in
  up)
    ensure_config
    ensure_keycloak_data_volume
    assert_compose_config
    docker compose --env-file "$(compose_env_file)" up -d --build
    verify_stack
    docker compose --env-file "$(compose_env_file)" ps
    PORTAL_PASSWORD=$(get_env_value DEV_PORTAL_ADMIN_PASSWORD)
    printf '\nGateway:         http://localhost:8086\nCheckout:        http://localhost:4200\nMerchant portal: http://localhost:4201\nAdmin portal:    http://localhost:4202\nKeycloak:        http://localhost:8180\nDev admin login: pgadmin / %s\n' "$PORTAL_PASSWORD"
    ;;
  rebuild)
    ensure_config
    ensure_keycloak_data_volume
    assert_compose_config
    docker compose --env-file "$(compose_env_file)" build --no-cache
    docker compose --env-file "$(compose_env_file)" up -d
    verify_stack
    docker compose --env-file "$(compose_env_file)" ps
    ;;
  verify)
    ensure_config
    verify_stack
    ;;
  down) docker compose --env-file "$(compose_env_file)" down ;;
  status) docker compose --env-file "$(compose_env_file)" ps ;;
  logs) if [ -n "$SERVICE" ]; then docker compose --env-file "$(compose_env_file)" logs -f --tail 200 "$SERVICE"; else docker compose --env-file "$(compose_env_file)" logs -f --tail 100; fi ;;
  *) echo "Usage: $0 {up|down|status|logs|rebuild|verify} [service]" >&2; exit 2 ;;
esac
