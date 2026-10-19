#!/bin/sh
set -eu
OIDC_BROWSER_BASE_URL="${OIDC_BROWSER_BASE_URL:-http://localhost:8180}"
GATEWAY_BROWSER_BASE_URL="${GATEWAY_BROWSER_BASE_URL:-http://localhost:8086}"
cat > /usr/share/nginx/html/runtime-config.js <<JS
window.__PAYMENT_PORTAL_CONFIG__ = { oidcBaseUrl: "${OIDC_BROWSER_BASE_URL}", gatewayBaseUrl: "${GATEWAY_BROWSER_BASE_URL}" };
JS
