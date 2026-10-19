# TXN HUB Payment Gateway — Current Project State

**Canonical project documentation**  
**Application version:** `1.0.0-SNAPSHOT`  
**Package revision:** `v20`  
**State date:** `2026-10-19`  
**Documentation policy:** This is the **only Markdown documentation file** kept in the source tree. Future changes must update this file instead of creating additional `.md` files.

---

## 1. Versioning policy

The project has two version identifiers:

| Identifier | Current value | Purpose |
|---|---:|---|
| Maven/application version | `1.0.0-SNAPSHOT` | Java artifact/application version inherited from the root Maven POM |
| Package revision | `v20` | Iteration number for the generated project ZIP and consolidated project state |

The plain-text `VERSION` file at the project root is the machine-readable version reference.

### Future update rules

1. Keep this file as the single documentation source of truth.
2. Do not add new `.md` files for individual fixes.
3. Add the new change to **Section 25 — Revision history**.
4. Increment `package_revision` in `VERSION` for each generated project revision.
5. Keep Maven `1.0.0-SNAPSHOT` while the project is pre-release. When an approved stable release is created, change the root Maven version and the `project_version` in `VERSION` together.
6. Use ZIP names in the form `payment-gateway-session-stabilized-v<revision>.zip` or a descriptive suffix followed by `v<revision>`.

---

## 2. Current technology baseline

The project intentionally retains the following development baseline:

| Technology | Current version / mode |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.1 |
| Apache Dubbo | 3.3.7-SNAPSHOT |
| Dubbo protocol | Triple (`tri`) |
| Spring Cloud Gateway | Reactive API gateway |
| Nacos | 2.5.1 local Docker image |
| Keycloak | 26.8.0 local development realm |
| PostgreSQL | 16 |
| Redis | 7.4 |
| Apache RocketMQ | 5.3.2 |
| Angular | 22 workspace |
| Merchant SDK | 1.0.0 |

The Spring Boot / Java / Dubbo versions above must not be downgraded merely to avoid compatibility work. Compatibility issues are handled explicitly in the source and runtime configuration.

---

## 3. Architecture overview

TXN HUB is a multi-provider payment gateway with a common payment domain and provider adapters.

```text
Merchant backend / public checkout client
                |
                v
        API Gateway :8086
                |
       +--------+---------+
       |                  |
 trusted PGSIG1      public bearer token
       |                  |
       +--------+---------+
                |
                v
      payment-api-service
                |
        Dubbo Triple/Nacos
                |
                v
      payment-core-service
                |
     ProviderClientRegistry
       /        |         \
      v         v          v
   ABank       MPU       AYA PAY
      |         |          |
      +---------+----------+
                |
       normalized payment state
                |
      PostgreSQL transactional outbox
                |
                v
           RocketMQ 5
                |
                v
      notification-service
                |
          merchant webhooks
```

The payment core contains no bank-specific HTTP mapping. Provider-specific behavior lives in provider adapter modules and is selected through provider capability metadata.

---

## 4. Repository/module layout

Important modules:

| Module/path | Responsibility |
|---|---|
| `api-gateway` | External routing, trusted request verification, public-token authorization, portal routing/security |
| `payment-api-service` | HTTP-facing payment, portal/admin and checkout APIs; Dubbo consumer |
| `payment-core-service` | Payment state machine, persistence, idempotency, provider orchestration, reconciliation |
| `provider-abank-service` | ABank/APlus MMQR adapter |
| `provider-mpu-service` | MPU hosted-payment adapter |
| `provider-ayapay-service` | AYA PAY QR/Push/customer-present QR/refund adapter |
| `callback-service` | Provider callback entry/normalization path |
| `notification-service` | Outbox publishing, RocketMQ consumption and merchant webhook delivery |
| `payment-contract` | Shared Dubbo/provider contracts |
| `payment-common` | Common domain/support code |
| `persistence-common` | Shared persistence/auditing support |
| `frontend/angular-workspace` | Hosted Checkout, Merchant Portal and Admin Portal |
| `sdk` | Merchant SDK source implementations |
| `sql` | Manual schema snapshot; Flyway remains authoritative |
| `deploy` | Keycloak realm/theme and runtime configuration assets |
| `scripts` | Local stack, validation and security helper scripts |

---

## 5. Local Docker stack

Preferred Windows startup from the project root:

```powershell
.\scripts\dev-stack.ps1 up
```

Useful commands:

```powershell
.\scripts\dev-stack.ps1 status
.\scripts\dev-stack.ps1 logs
.\scripts\dev-stack.ps1 logs -Service api-gateway
.\scripts\dev-stack.ps1 down
.\scripts\dev-stack.ps1 rebuild
```

Do **not** use `docker compose down -v` unless you intentionally want to destroy persistent development data.

### Local services

| Component | Local endpoint |
|---|---|
| API Gateway | `http://localhost:8086` |
| Payment API direct/debug | `http://localhost:8087` |
| Callback service direct/debug | `http://localhost:8083` |
| Hosted Checkout | `http://localhost:4200` |
| Merchant Portal | `http://localhost:4201` |
| Admin Portal | `http://localhost:4202` |
| Keycloak | `http://localhost:8180` |
| Nacos | `http://localhost:18848/nacos` |
| PostgreSQL | `localhost:15432` |
| Redis | `localhost:16379` |
| RocketMQ NameServer | `localhost:19876` |
| RocketMQ Proxy | `localhost:18081` |
| Local webhook receiver | `http://localhost:18090` |

Published local ports bind to `127.0.0.1` by default.

### Startup behavior

The local stack script can:

- create `.env` from `.env.example` when absent;
- generate development Keycloak/admin credentials;
- generate a public-token HMAC secret;
- generate the Gateway outbound-webhook Ed25519 key pair;
- initialize persistent Keycloak storage;
- repair local Keycloak provisioning-client secret mismatches against an existing persistent realm;
- initialize RocketMQ storage permissions/resources;
- wait for database schema readiness;
- create RocketMQ topic/group resources;
- seed local portal SUPER_ADMIN authorization;
- verify readiness of the critical stack components.

---

## 6. Persistent local data and Keycloak recovery

Local Keycloak uses the named volume:

```text
payment-gateway-dev-keycloak-data
```

This preserves dynamically provisioned merchant users across normal `down` / `up` cycles.

When a newly extracted project generates a new local provisioning secret while the persistent Keycloak realm still has the old secret, `dev-stack.ps1` can reconcile local development credentials by using Keycloak's offline bootstrap-admin recovery path, rotating the `payment-gateway-user-admin` client secret, updating `.env`, restoring the local `pgadmin` password, removing the temporary recovery identity, and recreating only the affected application service.

Admin Portal also provides **Repair login** for a merchant. The recovery workflow can reset an existing Keycloak merchant identity or recreate a missing identity and atomically update the local `portal_user.oidc_subject` binding. Temporary passwords are never stored in PostgreSQL.

---

## 7. Nacos and Dubbo Triple

Dubbo-enabled modules use Nacos registry/discovery and the Triple (`tri`) protocol. HTTP applications also register with Nacos so the Gateway can route by service ID (`lb://...`).

Local defaults:

```text
Nacos host: 127.0.0.1
Nacos port: 8848
Username: nacos
Password: nacos
Namespace: public
Dubbo protocol: tri
```

Nacos is used for discovery/registry; it is not relied on as the primary secret store. Production credentials should be externalized to an approved secret-management system.

---

## 8. Database and migrations

The main database is `payment_gateway` in PostgreSQL.

**Flyway migrations under `payment-core-service/src/main/resources/db/migration` are authoritative.**

`sql/payment_gateway_schema.sql` is a manual bootstrap/inspection snapshot and must not replace normal versioned migration control.

Core tables include:

```text
payment_provider
merchant
merchant_api_key
merchant_redirect_uri
portal_user
client_request_security_log
public_token_security_log
payment
payment_checkout_session
payment_provider_attempt_counter
provider_transaction
payment_refund
idempotency_record
provider_callback
outbox_event
inbox_event
merchant_webhook_endpoint
webhook_delivery
api_call_log
provider_api_exchange
```

Core JPA entities use optimistic/audit metadata such as `version`, `created_at`, and `updated_at` where applicable.

---

## 9. Merchant onboarding and lifecycle

The recommended onboarding lifecycle is:

```text
SUPER_ADMIN creates merchant
        |
        v
      PENDING
        |
        +--> Keycloak merchant portal administrator provisioned
        |
        +--> Merchant signs into :4201
        |
        +--> Merchant generates Ed25519 key locally
        |
        +--> only public key is registered
        |
        +--> Admin reviews readiness
        v
      ACTIVE
```

Important rules:

- New merchants start as `PENDING`.
- A `PENDING` merchant cannot use normal signed payment APIs.
- A `PENDING` merchant can use the minimal portal bootstrap APIs required to view/register signing keys.
- At least one `ACTIVE` Ed25519 public key is required before activation.
- Merchant private keys are never generated by, uploaded to, or stored by the Gateway.
- `SUSPENDED` or `CLOSED` merchants do not receive the bootstrap exception.

Admin Portal supports merchant user provisioning, login repair, readiness review, activation and redirect URL management.

---

## 10. Redirect/return URL management

A hosted checkout request may include `returnUrl`. This is the browser destination after checkout; it is not a webhook endpoint and must not be trusted as payment proof.

Each return URL must be registered for the merchant and `ACTIVE` before it can be used.

Admin endpoints:

```text
GET  /api/v1/portal/admin/merchants/{merchantId}/redirect-uris
POST /api/v1/portal/admin/merchants/{merchantId}/redirect-uris
PUT  /api/v1/portal/admin/merchants/{merchantId}/redirect-uris/{redirectUriId}
```

Admin Portal → Merchant Onboarding → **Return URLs** supports listing, adding, editing, enabling and disabling URLs.

For the local Spring Boot merchant test application, the intended return URL is:

```text
http://localhost:9090/payment-result
```

URL matching is exact; hostname, path, trailing slash and query changes are treated as different URLs.

---

## 11. Trusted merchant request security — PGSIG1

Trusted merchant/partner backends authenticate with Ed25519 request signatures.

Required headers:

```text
X-Merchant-Id: <merchant UUID>
X-Key-Id: <registered key ID>
X-Timestamp: <Unix epoch seconds>
X-Nonce: <unique 16..128 character value>
Idempotency-Key: <when the business operation requires it>
Content-Digest: sha-256=:<base64 SHA-256 of exact body bytes>:
X-Signature: <base64 Ed25519 signature>
```

Canonical input is UTF-8 with `\n` separators and no trailing newline:

```text
PGSIG1
<UPPERCASE METHOD>
<RAW PATH>
<RAW QUERY STRING OR EMPTY>
<X-Merchant-Id>
<X-Key-Id>
<X-Timestamp>
<X-Nonce>
<Idempotency-Key OR EMPTY>
<Content-Digest>
```

The exact request bytes hashed into `Content-Digest` must be the bytes sent on the wire.

### Retry behavior

For a legitimate retry of the same business operation:

- keep the same request body;
- reuse the same `Idempotency-Key`;
- generate a fresh timestamp;
- generate a fresh nonce;
- generate a fresh Ed25519 signature.

Replay protection and business idempotency are separate controls.

---

## 12. Duplicate merchant order handling and idempotency

`merchantOrderId` is unique per merchant.

A genuinely new request using an already-used `merchantOrderId` returns:

```http
HTTP/1.1 409 Conflict
```

with an error such as:

```json
{
  "error": "DUPLICATE_MERCHANT_ORDER",
  "message": "merchantOrderId 'SPRING-...' already exists for this merchant. Use a new merchantOrderId for a new payment, or retry the original request with the same Idempotency-Key.",
  "merchantOrderId": "SPRING-..."
}
```

The database unique constraint remains the final concurrency authority. The service also performs a fast pre-check and translates a race/constraint violation to the same business response.

Expected semantics:

```text
same business operation + same Idempotency-Key + same body
    -> return/reuse the original idempotent result

new Idempotency-Key + already-used merchantOrderId
    -> 409 DUPLICATE_MERCHANT_ORDER

same Idempotency-Key + conflicting request data
    -> idempotency conflict
```

---

## 13. Public client authentication

Public web/mobile/desktop clients do not receive merchant private keys.

A trusted merchant can request a short-lived payment-scoped HS256 bearer token from:

```text
POST /api/v1/security/public-tokens
```

Supported public scopes include:

```text
payment:create
payment:read
payment:status
customer-qr:inspect
customer-qr:pay
```

Public write-operation `jti` values are replay-protected through Redis. Refund, void and reconcile remain trusted merchant operations.

---

## 14. Provider adapters

### ABank / APlus

Module: `provider-abank-service`

Primary capabilities:

- MMQR create;
- enquiry/reconciliation;
- provider callback intake with independent status verification where required.

### MPU

Module: `provider-mpu-service`

Primary capabilities:

- hosted form-post flow;
- inquiry;
- frontend/backend callback handling;
- void.

### AYA PAY

Module: `provider-ayapay-service`

Primary capabilities:

- QR payment;
- Push payment;
- customer-present QR;
- status enquiry;
- encrypted callback processing;
- refund.

Provider-specific request data belongs in generic `providerOptions`. Common payment fields must remain outside `providerOptions`.

Provider behavior is resolved by capability/registry metadata so new adapters can be added without embedding bank-specific logic into the payment core.

---

## 15. Core payment API

Create payment:

```http
POST /api/v1/payments
```

Common request shape:

```json
{
  "merchantOrderId": "ORDER-1001",
  "amountMinor": 1000,
  "currency": "MMK",
  "description": "Example payment",
  "providerCode": "ABANK_APLUS",
  "providerOptions": {},
  "presentationMode": "HOSTED_PAGE",
  "returnUrl": "https://merchant.example/payment-result"
}
```

Supported presentation modes:

```text
HOSTED_PAGE
QR_STRING
```

Common operations:

```text
POST /api/v1/payments
GET  /api/v1/payments/{paymentId}
POST /api/v1/payments/{paymentId}/reconcile
POST /api/v1/payments/{paymentId}/refunds
POST /api/v1/payments/{paymentId}/void
GET  /api/v1/providers
POST /api/v1/customer-qr/inspect
POST /api/v1/customer-qr/pay
```

---

## 16. Hosted checkout flow

For `presentationMode=HOSTED_PAGE`:

```text
Merchant backend
      |
      | signed create-payment request
      v
TXN HUB Gateway
      |
      v
Provider payment initiation
      |
      v
QR/action generated
      |
      v
checkoutUrl returned
      |
      v
Customer browser -> http://localhost:4200/checkout/<token>
      |
      v
Customer completes/exits checkout
      |
      v
registered merchant returnUrl
```

The merchant must verify the authoritative payment state through a signed status API and/or webhook. A browser return alone is not proof of payment.

Checkout tokens are server-issued and stored as hashes; payment expiry is server-enforced.

---

## 17. Payment state, outbox and RocketMQ

Real payment status transitions are normalized in Payment Core. The transition and corresponding `PAYMENT_STATUS_CHANGED` outbox event are persisted transactionally.

```text
Payment state change
      |
      +--> payment row
      |
      +--> outbox_event
              |
              v
      notification-service
              |
              v
          RocketMQ 5
              |
              v
          inbox_event
              |
              v
       webhook_delivery
```

The outbox/inbox pattern prevents the database commit and asynchronous delivery pipeline from becoming an unsafe dual-write operation.

---

## 18. Merchant webhooks — PGWH1

Merchant webhook endpoints are server-to-server notification destinations and are separate from checkout `returnUrl` values.

Outbound Gateway webhook signature canonical data:

```text
PGWH1
<X-Payment-Event-Id>
<X-Payment-Timestamp>
<Content-Digest>
```

Important webhook headers include:

```text
X-Payment-Event-Id
X-Payment-Key-Id
X-Payment-Timestamp
Content-Digest
X-Payment-Signature
```

The merchant verifies the exact received body digest and Ed25519 signature using the TXN HUB webhook public key, then deduplicates by event ID.

Key ownership is intentionally asymmetric:

- Merchant → Gateway API requests: the merchant keeps the merchant **private** key; the Gateway stores only the merchant **public** key for PGSIG1 verification.
- Gateway → Merchant webhooks: `notification-service` keeps the TXN HUB webhook-signing **private** key; merchants receive the corresponding TXN HUB webhook **public** key for PGWH1 verification.
- The local Docker development signing key ID is `payment-gateway-webhook-dev-01`, and its private key is mounted only into `notification-service` at `/run/payment-gateway/secrets/payment-gateway-webhook-dev-01-private.pem`.

Webhook delivery states are tracked independently from payment states. One payment can generate multiple events and multiple delivery records.

Typical delivery states:

```text
DELIVERED
PENDING / SENDING
RETRY
DEAD
```

`RETRY` represents a delivery that may succeed later; `DEAD` represents an exhausted/dead delivery according to configured retry policy.

---

## 19. Portals and live updates

The Angular workspace contains:

```text
Hosted Checkout :4200
Merchant Portal :4201
Admin Portal    :4202
```

Admin and Merchant portal payment views use authenticated SSE for near-real-time updates, backed by the durable payment event path. A periodic 60-second safety refresh remains available.

The live path carries two lifecycle signals:

- `PAYMENT_CREATED` — emitted transactionally when a new payment row is committed. It exists specifically so totals and transaction lists update immediately even if provider initiation has not produced a later status transition yet. This event is portal-only and does not create merchant webhook deliveries.
- `PAYMENT_STATUS_CHANGED` — emitted whenever the authoritative payment status changes. It updates portal views and continues through the merchant webhook delivery path.

Both signals are now split into two paths after the committed outbox row is claimed:

- **Portal live path:** payment-core outbox → notification-service outbox relay → Redis Pub/Sub → api-gateway reactive SSE endpoint → authenticated Admin/Merchant portal stream. This happens before the RocketMQ send, so dashboard refresh does not depend on a RocketMQ consumer round trip.
- **Durable integration path:** payment-core outbox → notification-service RocketMQ producer → RocketMQ consumer → webhook/inbox processing. `PAYMENT_CREATED` is consumed as a no-op on this durable side so it does not create an extra merchant webhook delivery.

The SSE event is only a change notification. Browsers do not increment counters from event payloads; they reload authoritative summary/transaction REST APIs, so duplicate live events are harmless and temporary Redis/SSE loss is recovered by the safety sync.

The Admin Dashboard also displays live diagnostics: connection state plus the last received live event type, payment ID and receive time.

The browser SSE connection now terminates directly in the reactive API Gateway after OIDC validation; it is no longer proxied through the MVC payment-api service. SSE lifecycle remains route-scoped with reference-counted acquire/release behavior so Dashboard/Transactions live streams do not remain open when navigating to pages that do not need them. This avoids local HTTP/1.1 browser connection stalls such as SDK/API requests waiting in Chrome's `Stalled` phase.

Portal authorization explicitly resolves both the reactive security context and `portal_user` lookup before invoking the downstream `Mono<Void>` filter chain. It must not use `switchIfEmpty()` after downstream authorization/forwarding, because a successful `Mono<Void>` completes without emitting an item; treating that normal completion as "user not found" caused the v13 SSE endpoint to return `403 PORTAL_USER_NOT_REGISTERED` even when JWT `sub` and `portal_user.oidc_subject` matched.

The Gateway now owns Redis Pub/Sub through `ReactiveRedisMessageListenerContainer`, whose connection lifetime is tied to the Gateway bean rather than individual browser subscriptions. `PortalLiveRedisStream` creates subscriptions lazily with `Flux.defer`, retries a lost Lettuce subscription with bounded exponential backoff, and does not use the previous `publish().refCount(1)` sharing pattern. A final controller-side `onErrorResume(... -> Flux.never())` safety boundary ensures an unexpected live-event branch failure cannot terminate an already-committed SSE response; heartbeats and the 60-second authoritative REST safety sync remain available while Redis reconnects.

OIDC bearer access tokens are held in memory, refresh behavior is single-flight, and 401 recovery performs controlled refresh/retry behavior rather than creating parallel refresh storms.

---

## 20. Merchant SDKs

Server-side SDK source exists for:

```text
Java
PHP
Node.js
.NET
Python
```

Merchant Portal `/sdk` provides packaged SDK downloads. SDKs perform request serialization, `Content-Digest`, fresh timestamp/nonce generation, canonical PGSIG1 construction, Ed25519 signing and HTTP invocation locally. Merchant private keys are never sent to TXN HUB.

Supported helper operations include:

- create payment;
- get payment;
- reconcile payment;
- refund payment;
- void payment;
- generic signed request;
- PGWH1 webhook verification.

The standalone Markdown signing-spec download was removed from the source-tree asset set as part of documentation consolidation. The canonical PGSIG1 protocol is now maintained in **Section 11** of this file, while the packaged SDK ZIPs remain downloadable from Merchant Portal.

SDK version: `1.0.0`.

---

## 21. Admin Dashboard current behavior

Payment metrics and webhook-delivery metrics are intentionally separate.

### Payment summary and active charts

The top payment metrics continue to use the full payment population. The previous recent-amount line chart and all-time status donut were replaced in v17 with two live operational charts backed by `GET /api/v1/portal/admin/dashboard/activity?hours=24`:

- **Payment activity — last 24 hours:** one hourly bar per bucket, stacked by current payment status classification.
- **Provider activity — last 24 hours:** payment volume by active provider, with each provider bar split by current status classification.

Both charts are refreshed by the same SSE-triggered Dashboard reload path and by the 60-second safety sync, so new payments/status changes are reflected without changing pages when the live stream is healthy.

Classification:

```text
Succeeded:
  SUCCEEDED

In progress:
  PENDING
  QR_GENERATED
  ACTION_REQUIRED
  UNKNOWN

Failed / expired:
  FAILED
  EXPIRED
  VOIDED

Other:
  any status not covered above
```

### Webhook delivery health

A separate panel reports:

- total deliveries;
- delivered;
- pending/sending;
- retrying;
- dead;
- affected payments (distinct payments with RETRY or DEAD deliveries).

Recent transaction rows can expose aggregate webhook counts, and per-payment webhook detail shows endpoint, status, attempts, last HTTP status/error and the associated payment status transition.

---

## 22. Admin Transactions page

The Admin Transactions screen provides server-side pagination, per-payment webhook correlation and explicit column-oriented filters.

Visible/filtered transaction fields include:

| Column | Filter behavior |
|---|---|
| Merchant | Partial UUID, merchant code or merchant name |
| Order | Partial merchant order ID |
| Payment ID | Partial/full payment UUID |
| Amount | Exact minor-unit amount |
| Currency | Exact normalized currency |
| Provider | Partial provider code |
| Payment status | Exact payment status |
| Webhook | Aggregate state filter |
| Created | Inclusive date range |
| Action | Reconcile / details operations as authorized |

Current paginated endpoint:

```text
GET /api/v1/portal/admin/transactions/page
```

Supported parameters:

```text
merchant=
orderId=
paymentId=
amountMinor=
currency=
provider=
status=
webhook=
createdFrom=YYYY-MM-DD
createdTo=YYYY-MM-DD
page=
size=
```

Webhook aggregate filters include:

```text
ISSUES
RETRY
DEAD
PENDING
DELIVERED
NONE
```

`createdTo` is implemented as `< next day` internally so the selected date is inclusive without relying on a particular time-of-day.

Per-payment webhook detail endpoint:

```text
GET /api/v1/portal/admin/transactions/{paymentId}/webhooks
```

---

## 23. Build, verification and production cautions

### Maven build

```bash
mvn -U clean verify
```

The project targets Java 25 and uses Dubbo `3.3.7-SNAPSHOT`; the required snapshot repository must be available in the build environment.

### Source validation

```bash
python scripts/verify-source.py
```

The verifier is maintained with this consolidated documentation structure and treats `PROJECT_STATE.md` as the canonical project document.

### Production cautions

The Docker Compose topology is for local/integration development, not HA production. Production should use approved deployment controls including:

- managed/clustered PostgreSQL, Redis, RocketMQ, Nacos and OIDC where appropriate;
- TLS and production hostnames;
- production secret management;
- least-privilege service accounts;
- network policies/firewall controls;
- backup/restore and disaster-recovery procedures;
- image/dependency/security scanning;
- centralized logs, metrics and alerting;
- formal change approval and rollback plans.

Do not store production private keys, provider credentials or long-lived secrets in Git, ordinary Nacos configuration, committed `.env` files, tickets or chat systems.


---

## 24. Exception handling and external error contract

`payment-api-service` has one centralized `@RestControllerAdvice` exception boundary. Merchant-facing and portal-facing APIs must not expose Java class names, SQL constraint text, Hibernate/Dubbo stack traces, provider credentials, Keycloak secrets or raw infrastructure exceptions.

External API errors use this stable envelope:

```json
{
  "timestamp": "2026-10-19T08:30:00Z",
  "status": 409,
  "error": "Conflict",
  "code": "IDEMPOTENCY_CONFLICT",
  "message": "Idempotency-Key was already used with a different request",
  "path": "/api/v1/payments",
  "correlationId": "6b2c...",
  "details": {}
}
```

`X-Correlation-Id` is generated/forwarded by `api-gateway`, returned by the gateway, logged by the API exception boundary, and included in the body when present. Operators should use this value to correlate client errors with gateway/API/audit logs rather than exposing stack traces to clients.

### Stable payment/API error codes

| HTTP | Code | Meaning |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Bean/constraint validation failed; field details may be included |
| 400 | `MISSING_REQUIRED_HEADER` | Required HTTP header missing |
| 400 | `MISSING_REQUIRED_PARAMETER` | Required query/form parameter missing |
| 400 | `INVALID_PARAMETER` | Parameter type/value could not be converted |
| 400 | `MALFORMED_JSON` | Missing or malformed JSON request body |
| 400 | `INVALID_REQUEST` | Semantically invalid request/input |
| 401/403 | Gateway security codes | Authentication/signature/token/RBAC rejection; handled at the gateway security boundary |
| 404 | `PAYMENT_NOT_FOUND` | Payment does not exist for the authenticated merchant |
| 404 | `IDENTITY_NOT_FOUND` | Expected Keycloak identity no longer exists |
| 405 | `METHOD_NOT_ALLOWED` | Unsupported HTTP method |
| 409 | `DUPLICATE_MERCHANT_ORDER` | `merchantOrderId` already belongs to another payment for the merchant |
| 409 | `IDEMPOTENCY_CONFLICT` | Same Idempotency-Key reused with a different request |
| 409 | `INVALID_PAYMENT_STATE` | Operation is not allowed for the current payment state |
| 409 | `DATA_CONFLICT` | Request conflicts with an existing database record |
| 409 | `IDENTITY_CONFLICT` | Username/email or external identity conflict |
| 409 | `MERCHANT_ONBOARDING_CONFLICT` | Merchant onboarding conflicts with existing state |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Unsupported request `Content-Type` |
| 422 | `PROVIDER_NOT_AVAILABLE` | Requested provider is disabled/not configured |
| 422 | `UNSUPPORTED_PROVIDER_CAPABILITY` | Provider does not support the requested operation |
| 422 | `PROVIDER_REJECTED` | Provider rejected the requested provider operation |
| 502 | `IDENTITY_PROVIDER_ERROR` | Keycloak/identity-provider operation failed |
| 503 | `PROVIDER_UNAVAILABLE` | Provider adapter/configuration is temporarily unavailable |
| 503 | `UPSTREAM_UNAVAILABLE` | Dubbo/service-registry/upstream service is unavailable |
| 504 | `UPSTREAM_TIMEOUT` | Upstream/provider/RPC operation timed out |
| 500 | `INTERNAL_ERROR` | Unexpected internal error; only generic text is returned to clients |

Spring Framework 7 status mappings use `HttpStatus.UNPROCESSABLE_CONTENT` for HTTP 422 responses; the deprecated `HttpStatus.UNPROCESSABLE_ENTITY` constant is not used.

### Project-wide JavaDoc policy

All Java source areas now follow project-wide JavaDoc coverage rather than documenting only exception-related code. Named classes, interfaces, enums, records and annotation types are documented, and every explicitly declared constructor/method has JavaDoc with applicable `@param`, `@return`, and declared `@throws` tags. Coverage includes production modules, provider adapters, SDK sources, tests and standalone Java security/quality utilities. Anonymous classes cannot carry a type-level JavaDoc declaration, but explicitly overridden methods inside them are documented.

`java scripts/quality/JavaDocCoverageCheck.java .` performs an AST-based coverage check using the JDK compiler API. The v20 package validates 230 Java source files, 270 named type declarations and 845 explicit methods/constructors with no undocumented declarations.

The payment-core module uses shared exceptions from `payment-contract` for errors that can cross the Dubbo boundary (`PaymentNotFoundException`, `IdempotencyConflictException`, `InvalidPaymentStateException`, provider capability/availability exceptions and duplicate merchant order). `payment-api-service` also contains compatibility classification for wrapped/older Dubbo exceptions so the external HTTP contract stays stable during rolling development upgrades.

`callback-service` has its own provider-facing exception boundary. It returns sanitized callback errors while logging the full internal exception with the correlation ID.

The Java SDK parses the structured error envelope into `TxnHubException.errorCode()`, `apiMessage()` and `correlationId()` while retaining the raw response body for controlled diagnostics.

---

## 25. Revision history

This table replaces the previous collection of one-off fix Markdown files.

| Revision | Date | Consolidated state/change |
|---|---|---|
| v1-v4 | 2026-10-09 to 2026-10-16 | Initial provider/plugin architecture, security hardening, dual auth, Spring Boot 4/Flyway compatibility, Docker runtime consolidation, Angular portals, branding, live SSE, SDK delivery, Keycloak persistence/recovery and RocketMQ startup stabilization |
| v5 | 2026-10-16 | Duplicate `merchantOrderId` now returns HTTP 409 `DUPLICATE_MERCHANT_ORDER`; Admin Portal can list/add/edit/enable/disable merchant return URLs |
| v6 | 2026-10-16 | Admin Dashboard separates payment counts from webhook delivery counts and adds per-payment webhook correlation |
| v7 | 2026-10-16 | Admin Transactions page adds webhook detail, server-side pagination and filtering |
| v8 | 2026-10-16 | Transactions filtering becomes column-oriented; Payment ID is visible; created-from/to date filtering added |
| v9 | 2026-10-16 | Documentation consolidation: 75 scattered `.md` files replaced by this single canonical current-state document; verifier and Merchant SDK page updated for the new structure; machine-readable `VERSION` file added for future revisions |
| **v10** | **2026-10-19** | **Added durable `PAYMENT_CREATED` outbox event so new-payment totals had a portal-live signal, while merchant webhooks remained limited to `PAYMENT_STATUS_CHANGED`.** |
| **v11** | **2026-10-19** | **Decoupled portal SSE from the RocketMQ consumer round trip: committed outbox events publish directly to Redis/SSE before RocketMQ send; durable webhook processing remains on RocketMQ. Added Admin Dashboard last-live-event diagnostics.** |
| **v12** | **2026-10-19** | **Moved portal SSE termination into the reactive API Gateway itself. The gateway subscribes directly to the Redis live-event channel after OIDC authentication, eliminating the unstable long-lived gateway→payment-api proxy hop. Added browser-visible SSE HTTP/error diagnostics.** |
| **v13** | **2026-10-19** | **Corrected the v12 Maven model by versioning the new `api-gateway -> payment-common` dependency, aligned the local webhook-signing private-key path with the generated key ID, and added startup/source verification so missing key mounts or unversioned internal dependencies fail early.** |
| **v14** | **2026-10-19** | **Fixed the reactive portal authorization false-empty bug that returned `403 PORTAL_USER_NOT_REGISTERED` for valid OIDC users after a successful `Mono<Void>` filter chain. Added explicit authentication/user empty-state branching plus SSE-specific regression tests so `/api/v1/portal/live/events` can remain connected for registered Admin/Merchant users.** |
| **v15** | **2026-10-19** | **Fixed repeated SSE disconnects caused by a closed Lettuce Pub/Sub connection (`Unable to connect; Connection is closed`). Replaced the template/shared `publish().refCount(1)` lifecycle with a dedicated `ReactiveRedisMessageListenerContainer`, lazy `Flux.defer` subscriptions, bounded exponential retry, and a controller safety boundary that keeps heartbeats alive if the live-event branch fails. Added verifier rules to prevent regression to the closed-connection lifecycle.** |
| **v16** | **2026-10-19** | **Fixed API Gateway test compilation by adding an explicit test-scoped `io.projectreactor:reactor-test` dependency required by `PortalLiveSseControllerTest` (`StepVerifier`). Added a source-verifier guard so future SSE regression tests cannot reference Reactor Test without declaring the dependency.** |
| **v17** | **2026-10-19** | **Replaced the Dashboard recent-amount line and all-time status donut with live payment-activity charts: hourly payment status activity and provider payment activity for the last 24 hours. Added a dedicated Admin activity endpoint and wired it into the existing SSE/safety-sync refresh cycle.** |
| **v18** | **2026-10-19** | **Implemented centralized exception handling and a stable external error envelope with correlation IDs, validation details, explicit payment/idempotency/provider/identity/upstream error codes, sanitized 5xx responses, shared Dubbo-crossing domain exceptions, callback-service error sanitization, and Java SDK structured error parsing.** |
| **v19** | **2026-10-19** | **Updated Spring Framework 7 HTTP 422 mappings from deprecated `HttpStatus.UNPROCESSABLE_ENTITY` to `HttpStatus.UNPROCESSABLE_CONTENT`. Added method-level JavaDoc to the centralized exception handlers and related shared exception/SDK/state-transition methods, and strengthened source verification against reintroducing the deprecated status constant.** |
| **v20** | **2026-10-19** | **Expanded JavaDoc from exception-focused coverage to the entire Java codebase. Added documentation for all named Java types and all explicit constructors/methods across production services, provider adapters, SDKs, tests and standalone Java utilities. Added the AST-based `JavaDocCoverageCheck` utility to prevent undocumented Java declarations from being reintroduced.** |

### Next revision

For the next generated project state:

1. increment `package_revision` in `VERSION` from `20` to `21`;
2. update the header/package revision to the next number;
3. add one new row to this revision history;
4. update existing sections to describe the new current behavior;
5. do not create another Markdown file.

---

## 26. Quick operational checklist

Before testing a merchant payment locally:

1. `dev-stack.ps1 up` completes readiness checks.
2. Merchant exists and is `ACTIVE`.
3. Merchant has an `ACTIVE` Ed25519 public key.
4. Merchant backend has the matching private key locally.
5. Required provider credentials are configured.
6. For `HOSTED_PAGE`, the exact `returnUrl` is registered and `ACTIVE`.
7. Use a new `merchantOrderId` for a new payment.
8. Use the same `Idempotency-Key` only for a legitimate retry of the same business operation.
9. Treat `returnUrl` as browser navigation only; verify final payment state using the Gateway status API and/or signed webhook.
10. Inspect per-payment webhook delivery details independently from payment status when troubleshooting notifications.
