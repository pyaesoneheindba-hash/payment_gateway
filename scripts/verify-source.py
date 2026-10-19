#!/usr/bin/env python3
from pathlib import Path
import sys, json, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]; errors=[]
for pom in root.rglob('pom.xml'):
    try: ET.parse(pom)
    except Exception as exc: errors.append(f'{pom}: invalid XML: {exc}')

# Every direct dependency on another reactor module must declare a version.
# This catches Maven model failures such as: 'dependencies.dependency.version ... is missing'.
_maven_ns={'m':'http://maven.apache.org/POM/4.0.0'}
for pom in root.rglob('pom.xml'):
    try:
        parsed=ET.parse(pom).getroot()
    except Exception:
        continue
    for dep in parsed.findall('.//m:dependencies/m:dependency', _maven_ns):
        group=dep.findtext('m:groupId', default='', namespaces=_maven_ns)
        artifact=dep.findtext('m:artifactId', default='', namespaces=_maven_ns)
        version=dep.findtext('m:version', default=None, namespaces=_maven_ns)
        if group == 'com.paymentgateway' and version is None:
            errors.append(f'{pom}: internal dependency {group}:{artifact} is missing an explicit version')
for java in root.rglob('*.java'):
    text=java.read_text(encoding='utf-8')
    if 'import javax.' in text and 'javax.crypto' not in text and 'javax.tools' not in text: errors.append(f'{java}: legacy javax import')
    if 'new ContentCachingRequestWrapper(request)' in text: errors.append(f'{java}: obsolete Spring 7 request-wrapper constructor')
    if 'verified.paymentTxnStatus()' in text: errors.append(f'{java}: obsolete EnquirePaymentResult accessor')
    if '\n@Entity\n' in text and 'extends BaseAuditableEntity' not in text: errors.append(f'{java}: JPA entity does not inherit BaseAuditableEntity')
sql=list(root.rglob('*.sql'))
expected_sql={root/'sql/payment_gateway_schema.sql', root/'payment-core-service/src/main/resources/db/migration/V1__baseline_schema.sql', root/'payment-core-service/src/main/resources/db/migration/V1_1__legacy_schema_completion.sql', root/'payment-core-service/src/main/resources/db/migration/V2__production_hardening.sql', root/'payment-core-service/src/main/resources/db/migration/V3__hosted_checkout_schema_compatibility.sql', root/'payment-core-service/src/main/resources/db/migration/V4__runtime_consolidation.sql', root/'deploy/dev/seed-portal-user.sql'}
if set(sql)!=expected_sql: errors.append(f'unexpected SQL file set: {sql}')
required=[root/'sql/payment_gateway_schema.sql',root/'provider-abank-service',root/'provider-mpu-service',root/'provider-ayapay-service',root/'PROJECT_STATE.md',root/'VERSION',root/'scripts/security/GenerateMerchantEd25519KeyPair.java',root/'scripts/security/SignHttpRequest.java',root/'scripts/security/GeneratePublicTokenSecret.java',root/'scripts/security/GenerateGatewayWebhookKeyPair.java',root/'scripts/security/VerifyWebhookSignature.java',root/'scripts/quality/JavaDocCoverageCheck.java']
for item in required:
    if not item.exists(): errors.append(f'missing: {item}')
rootpom=(root/'pom.xml').read_text()
if '<module>provider-ayapay-service</module>' not in rootpom: errors.append('root pom missing AYA PAY module')
if '<module>notification-service</module>' not in rootpom: errors.append('root pom missing notification-service module')
schema=(root/'sql/payment_gateway_schema.sql').read_text()
for token in ['payment_refund','CUSTOMER_PRESENT_QR','service_version','rpc_timeout_ms','merchant_api_key','client_request_security_log','public_token_security_log','merchant_webhook_endpoint','webhook_delivery','ck_client_security_decision','ck_public_token_security_decision']:
    if token not in schema: errors.append(f'schema missing {token}')
core='\n'.join(p.read_text() for p in (root/'payment-core-service/src/main/java').rglob('*.java'))
for token in ['provider-abank','provider-mpu','provider-ayapay']:
    if token in core: errors.append(f'payment-core contains hard-coded provider group: {token}')

# Nacos HTTP discovery / gateway load-balancing checks.
http_modules = ['api-gateway', 'payment-api-service', 'callback-service']
for mod in http_modules:
    pom = (root/mod/'pom.xml').read_text()
    if 'spring-cloud-starter-alibaba-nacos-discovery' not in pom:
        errors.append(f'{mod}: missing Nacos discovery starter')
    app_java = '\n'.join(x.read_text() for x in (root/mod/'src/main/java').rglob('*.java'))
    if '@EnableDiscoveryClient' not in app_java:
        errors.append(f'{mod}: missing @EnableDiscoveryClient')
    yml = (root/mod/'src/main/resources/application.yml').read_text()
    if 'spring:' not in yml or 'nacos:' not in yml or 'discovery:' not in yml:
        errors.append(f'{mod}: missing Spring Cloud Nacos discovery configuration')

gateway_pom=(root/'api-gateway/pom.xml').read_text()
if 'spring-cloud-starter-loadbalancer' not in gateway_pom:
    errors.append('api-gateway: missing Spring Cloud LoadBalancer')
gateway_yml=(root/'api-gateway/src/main/resources/application.yml').read_text()
for token in ['lb://payment-api-service','lb://callback-service']:
    if token not in gateway_yml: errors.append(f'api-gateway route missing {token}')
for token in ['http://127.0.0.1:8081','http://127.0.0.1:8083','PAYMENT_API_URI','CALLBACK_API_URI']:
    if token in gateway_yml: errors.append(f'api-gateway still contains fixed backend route token: {token}')
if 'webflux:\n          discovery:\n            locator:\n              enabled: false' not in gateway_yml:
    errors.append('api-gateway should keep server.webflux automatic discovery locator disabled')

# Client request cryptographic signature + replay protection checks.
for token in ['spring-boot-starter-data-r2dbc','r2dbc-postgresql','spring-boot-starter-data-redis-reactive','nimbus-jose-jwt']:
    if token not in gateway_pom: errors.append(f'api-gateway security dependency missing {token}')
for token in ['r2dbc:','redis:','client-signature:','CLIENT_SIGNATURE_ENABLED','replay-ttl-seconds','public-token:','PUBLIC_TOKEN_HMAC_SECRET_BASE64','forward:///internal/security/public-tokens']:
    if token not in gateway_yml: errors.append(f'api-gateway security configuration missing {token}')
gateway_security='\n'.join(p.read_text() for p in (root/'api-gateway/src/main/java').rglob('*.java'))
for token in ['ClientRequestSignatureFilter','Ed25519','Content-Digest','ReactiveStringRedisTemplate','merchant_api_key','client_request_security_log','PublicClientTokenFilter','PublicPaymentTokenService','HS256','public_token_security_log','PUBLIC_TOKEN']:
    if token not in gateway_security: errors.append(f'api-gateway security implementation missing {token}')
if 'x-signature' not in (root/'payment-api-service/src/main/java/com/paymentgateway/api/web/ApiCallAuditFilter.java').read_text().lower():
    errors.append('payment-api-service audit filter should redact X-Signature')
for token in ['merchant_api_key','client_request_security_log','public_token_security_log']:
    if token not in schema: errors.append(f'schema missing security table {token}')
if "'merchant_api_key','client_request_security_log','public_token_security_log'" not in schema:
    errors.append('updated_at trigger list missing gateway security tables')
# Dual client authentication + merchant ownership checks.
contract=(root/'payment-contract/src/main/java/com/paymentgateway/contract/payment/PaymentRpcService.java').read_text()
for token in ['getPayment(UUID merchantId, UUID paymentId)','reconcilePayment(UUID merchantId, UUID paymentId)']:
    if token not in contract: errors.append(f'PaymentRpcService missing merchant-owned method: {token}')
core_impl=(root/'payment-core-service/src/main/java/com/paymentgateway/core/service/PaymentRpcServiceImpl.java').read_text()
if 'persistence.requireOwned(id,merchantId)' not in core_impl:
    errors.append('PaymentRpcServiceImpl must enforce merchant ownership for payment reads/reconcile')
public_filter=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/publictoken/PublicClientTokenFilter.java').read_text()
for token in ['payment:create','payment:read','payment:status','customer-qr:inspect','customer-qr:pay','PUBLIC_TOKEN_REPLAY_DETECTED','GatewaySecurityHeaders.AUTH_MODE_PUBLIC_TOKEN']:
    if token not in public_filter: errors.append(f'public token filter missing {token}')


# Payment status notification checks.
if '/api/v1/public/payments/**' not in gateway_yml:
    errors.append('api-gateway missing public payment status route')
if '/api/v1/webhooks/**' not in gateway_yml:
    errors.append('api-gateway missing merchant webhook management route')
if 'payment:status' not in gateway_yml:
    errors.append('api-gateway public token config missing payment:status scope')
core_persistence=(root/'payment-core-service/src/main/java/com/paymentgateway/core/service/PaymentPersistenceService.java').read_text()
for token in ['PAYMENT_STATUS_CHANGED','previousStatus','PROVIDER_ENQUIRY_VERIFIED']:
    if token not in core_persistence: errors.append(f'payment core status outbox missing {token}')
status_controller=root/'payment-api-service/src/main/java/com/paymentgateway/api/web/PublicPaymentStatusController.java'
if not status_controller.exists(): errors.append('missing PublicPaymentStatusController')
webhook_controller=root/'payment-api-service/src/main/java/com/paymentgateway/api/webhook/MerchantWebhookController.java'
if not webhook_controller.exists(): errors.append('missing MerchantWebhookController')
notification=root/'notification-service/src/main/java/com/paymentgateway/notification/WebhookDispatcher.java'
if not notification.exists():
    errors.append('missing notification-service WebhookDispatcher')
else:
    notification_text=notification.read_text()
    for token in ['webhook_delivery','FOR UPDATE SKIP LOCKED','X-Payment-Signature']:
        if token not in notification_text: errors.append(f'notification dispatcher missing {token}')
rocket_publisher=root/'notification-service/src/main/java/com/paymentgateway/notification/RocketMqOutboxPublisher.java'
rocket_consumer=root/'notification-service/src/main/java/com/paymentgateway/notification/PaymentStatusEventHandler.java'
rocket_client=root/'notification-service/src/main/java/com/paymentgateway/notification/RocketMqClientManager.java'
for item in [rocket_publisher, rocket_consumer, rocket_client]:
    if not item.exists(): errors.append(f'missing RocketMQ implementation: {item}')
if rocket_publisher.exists():
    for token in ['outbox_event','FOR UPDATE SKIP LOCKED','PUBLISHED','DEAD']:
        if token not in rocket_publisher.read_text(): errors.append(f'RocketMQ outbox publisher missing {token}')
if rocket_consumer.exists():
    for token in ['inbox_event','PAYMENT_STATUS_CHANGED','ON CONFLICT(event_id,endpoint_id) DO NOTHING']:
        if token not in rocket_consumer.read_text(): errors.append(f'RocketMQ inbox consumer missing {token}')
signer=root/'notification-service/src/main/java/com/paymentgateway/notification/WebhookSigner.java'
if not signer.exists() or 'PGWH1' not in signer.read_text() or 'Ed25519' not in signer.read_text():
    errors.append('notification webhook Ed25519 PGWH1 signer missing')
for token in ['merchant_webhook_endpoint','webhook_delivery']:
    if token not in schema: errors.append(f'schema missing notification table {token}')

# 2026 production-hardening checks.
for token in ['payment_provider_attempt_counter','inbox_event','publish_attempt_count','oidc_subject','fk_payment_merchant']:
    if token not in schema: errors.append(f'hardened schema missing {token}')
core_state=root/'payment-core-service/src/main/java/com/paymentgateway/core/domain/PaymentStateTransitions.java'
if not core_state.exists(): errors.append('missing authoritative PaymentStateTransitions')
if 'findByIdForUpdate' not in core_persistence or 'sumReservedAmountByPaymentId' not in core_persistence:
    errors.append('refund/payment concurrency hardening is missing')
if 'countByPaymentId(paymentId) + 1' in core_persistence:
    errors.append('provider attempt number still uses COUNT + 1')
checkout=(root/'payment-api-service/src/main/java/com/paymentgateway/api/checkout/CheckoutSessionService.java').read_text()
if "UPDATE payment\n" in checkout or "UPDATE payment SET" in checkout:
    errors.append('checkout service still mutates payment state directly')
if 'paymentRpcService.expirePayment' not in checkout:
    errors.append('checkout expiry must delegate to payment-core')
portal_controller=(root/'payment-api-service/src/main/java/com/paymentgateway/api/portal/PortalController.java').read_text()
for token in ['PortalAuthorization','requireMerchantKeyAdmin','requireMerchantWebhookAdmin','requireSuperAdmin','WebhookUrlPolicy']:
    if token not in portal_controller: errors.append(f'portal server-side authorization missing {token}')
portal_security=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/portal/PortalSecurityConfig.java').read_text()
for token in ['oauth2ResourceServer','pathMatchers("/api/v1/portal/**")','@Order(0)','@Order(1)']:
    if token not in portal_security: errors.append(f'portal OIDC security missing {token}')
for legacy in ['PortalAuthController.java','PortalTokenService.java','PortalPassword.java']:
    if (root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/portal'/legacy).exists(): errors.append(f'legacy portal password auth still present: {legacy}')
for legacy_script in ['CreatePortalUser.java','GeneratePortalTokenSecret.java']:
    if (root/'scripts/security'/legacy_script).exists(): errors.append(f'legacy local portal auth utility still present: {legacy_script}')
if not (root/'scripts/security/RegisterPortalOidcUser.java').exists(): errors.append('missing OIDC portal entitlement registration utility')
if '/api/v1/portal/auth/login' in gateway_yml:
    errors.append('legacy local portal login route still configured')
if 'spring-boot-starter-flyway' not in (root/'payment-core-service/pom.xml').read_text(): errors.append('payment-core missing Spring Boot 4 Flyway starter')
core_yml=(root/'payment-core-service/src/main/resources/application.yml').read_text()
if 'baseline-version: 0' not in core_yml: errors.append('payment-core Flyway baseline-version must be 0 so legacy non-Flyway databases still execute V1')
if 'rocketmq-client-java' not in (root/'notification-service/pom.xml').read_text(): errors.append('notification-service missing RocketMQ client')

for private_key in root.rglob('*-private.pem'):
    errors.append(f'private signing key must not be packaged: {private_key}')

# Runtime-consolidation / Docker / onboarding checks.
for required_path in [
        root/'compose.yaml',
        root/'deploy/docker/Dockerfile.backend',
        root/'deploy/docker/Dockerfile.frontend',
        root/'scripts/dev-stack.ps1',
        root/'scripts/dev-stack.sh',
        root/'PROJECT_STATE.md',
        root/'payment-api-service/src/main/java/com/paymentgateway/api/portal/MerchantOnboardingService.java',
        root/'payment-core-service/src/main/resources/db/migration/V4__runtime_consolidation.sql']:
    if not required_path.exists(): errors.append(f'runtime consolidation missing: {required_path}')

all_text = '\n'.join(
    p.read_text(encoding='utf-8', errors='ignore')
    for p in root.rglob('*') if p.is_file() and p.suffix.lower() in {'.java','.yml','.yaml','.json','.md','.ts'}
    and '.git' not in p.parts and 'node_modules' not in p.parts
)
for stale in ['http://127.0.0.1:8080', 'http://localhost:8080/callbacks']:
    if stale in all_text: errors.append(f'stale pre-consolidation endpoint remains: {stale}')

old_topic_hits = []
for p in root.rglob('*'):
    if p.is_file() and p.suffix.lower() in {'.java','.yml','.yaml','.md'} and 'target' not in p.parts:
        text = p.read_text(encoding='utf-8', errors='ignore')
        if 'payment.lifecycle.v1' in text and p.name != 'V4__runtime_consolidation.sql' and p.name != 'PROJECT_STATE.md':
            old_topic_hits.append(str(p))
if old_topic_hits: errors.append(f'invalid RocketMQ topic remains outside migration/history docs: {old_topic_hits}')

onboarding=(root/'payment-api-service/src/main/java/com/paymentgateway/api/portal/MerchantOnboardingService.java').read_text()
for token in ["'PENDING'", 'validateEd25519PublicKey', 'readyForActivation', 'At least one ACTIVE Ed25519 public key']:
    if token not in onboarding: errors.append(f'merchant onboarding missing {token}')
merchant_repo=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/MerchantSigningKeyRepository.java').read_text()
merchant_filter=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/ClientRequestSignatureFilter.java').read_text()
if 'merchant_status' not in merchant_repo or 'MERCHANT_NOT_ACTIVE' not in merchant_filter:
    errors.append('gateway merchant-signed requests must enforce merchant ACTIVE state')
portal_filter=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/portal/PortalTokenFilter.java').read_text()
if 'MERCHANT_NOT_ACTIVE' not in portal_filter or 'isPendingMerchantSelfServicePath' not in portal_filter or '/api/v1/portal/merchant/api-keys' not in portal_filter:
    errors.append('merchant portal must enforce ACTIVE state except the narrow PENDING public-key bootstrap path')

merchant_integrations=(root/'frontend/angular-workspace/projects/merchant-portal/src/app/features/integrations/integrations.component.ts').read_text()
for token in ['crypto.subtle.generateKey', "exportKey('pkcs8'", 'downloadPrivateKey', 'privateKeySaved']:
    if token not in merchant_integrations: errors.append(f'merchant self-service key bootstrap missing {token}')
portal_controller=(root/'payment-api-service/src/main/java/com/paymentgateway/api/portal/PortalController.java').read_text()
if 'validateEd25519PublicKey' not in portal_controller:
    errors.append('merchant portal public-key registration must cryptographically validate Ed25519 X.509 PEM')

identity_provisioner=(root/'payment-api-service/src/main/java/com/paymentgateway/api/portal/KeycloakPortalIdentityProvisioner.java')
if not identity_provisioner.exists():
    errors.append('missing Keycloak merchant portal identity provisioner')
else:
    identity_text=identity_provisioner.read_text()
    for token in ['grant_type=client_credentials', '/admin/realms/', 'UPDATE_PASSWORD', 'service-account']:
        if token not in identity_text: errors.append(f'Keycloak merchant user provisioning missing {token}')
admin_merchants=(root/'frontend/angular-workspace/projects/admin-portal/src/app/features/merchants/merchants.component.ts').read_text()
for stale in ['portalOidcSubject', 'OIDC subject<input']:
    if stale in admin_merchants: errors.append(f'admin onboarding must not ask operators for raw OIDC subject: {stale}')
for token in ['portalTemporaryPassword', 'confirmTemporaryPassword', 'Require password change on first login']:
    if token not in admin_merchants: errors.append(f'admin onboarding merchant-user provisioning missing {token}')

compose=(root/'compose.yaml').read_text()
for token in ['rocketmq-init:', 'dev-seed:', 'admin-portal:', 'merchant-portal:', 'notification-service:', 'payment_lifecycle_v1']:
    if token not in compose: errors.append(f'compose stack missing {token}')
for token in ['payment-keycloak-data:/opt/keycloak/data', 'payment-gateway-dev-keycloak-data']:
    if token not in compose: errors.append(f'Keycloak persistence missing {token}')

# Local webhook signing-key generation/config/mount must agree exactly.
_expected_webhook_key='payment-gateway-webhook-dev-01-private.pem'
notification_docker=(root/'notification-service/src/main/resources/application-docker.yml').read_text()
if f'/run/payment-gateway/secrets/{_expected_webhook_key}' not in notification_docker:
    errors.append('notification-service Docker webhook private-key path does not match generated key ID')
if './.runtime-secrets:/run/payment-gateway/secrets:ro' not in compose:
    errors.append('notification-service runtime-secrets mount missing')
for script_name in ['dev-stack.ps1','dev-stack.sh']:
    script_text=(root/'scripts'/script_name).read_text()
    if 'payment-gateway-webhook-dev-01' not in script_text:
        errors.append(f'{script_name}: local webhook signing key ID mismatch')
repair_service=root/'payment-api-service/src/main/java/com/paymentgateway/api/portal/MerchantPortalLoginRepairService.java'
if not repair_service.exists():
    errors.append('missing MerchantPortalLoginRepairService')
else:
    repair_text=repair_service.read_text()
    for token in ['RESET_EXISTING_IDENTITY','RECREATED_MISSING_IDENTITY','oidc_subject']:
        if token not in repair_text: errors.append(f'merchant portal login repair missing {token}')
if '/portal-login/repair' not in portal_controller:
    errors.append('admin merchant portal-login repair endpoint missing')
if 'repairMerchantPortalLogin' not in admin_merchants:
    errors.append('admin portal Repair login UI missing')

# Portal live SSE Redis lifecycle regression checks.
# Browser connect/disconnect cycles must not share a template-bound Pub/Sub Flux via
# publish().refCount(1), because the bound Lettuce connection is released on cancellation
# and later SSE subscribers can fail with "Unable to connect; Connection is closed".
_live_stream_path=root/'api-gateway/src/main/java/com/paymentgateway/gateway/portal/live/PortalLiveRedisStream.java'
_live_stream=_live_stream_path.read_text()
for token in ['ReactiveRedisMessageListenerContainer', 'Flux.defer', 'container.receive(topic)', 'retryWhen', 'Retry.backoff']:
    if token not in _live_stream:
        errors.append(f'PortalLiveRedisStream resilient Redis lifecycle missing {token}')
for stale in ['.publish()', '.refCount(']:
    if stale in _live_stream:
        errors.append(f'PortalLiveRedisStream must not use template/shared Pub/Sub lifecycle token {stale}')
_live_controller=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/portal/live/PortalLiveSseController.java').read_text()
for token in ['onErrorResume', 'keeping SSE heartbeat alive', 'Flux.never()']:
    if token not in _live_controller:
        errors.append(f'PortalLiveSseController final SSE safety boundary missing {token}')
_live_sse_test=(root/'api-gateway/src/test/java/com/paymentgateway/gateway/portal/live/PortalLiveSseControllerTest.java').read_text()
for token in ['redisBranchFailureDoesNotTerminateConnectedSseHeartbeat', 'StepVerifier.withVirtualTime', 'Unable to connect; Connection is closed']:
    if token not in _live_sse_test:
        errors.append(f'Portal live SSE recovery regression test missing {token}')
_api_gateway_pom=(root/'api-gateway/pom.xml').read_text()
if 'reactor.test' in _live_sse_test:
    if '<groupId>io.projectreactor</groupId><artifactId>reactor-test</artifactId><scope>test</scope>' not in _api_gateway_pom:
        errors.append('api-gateway tests import reactor.test but api-gateway/pom.xml is missing test-scoped io.projectreactor:reactor-test')
for yml_name in ['application.yml', 'application-docker.yml']:
    yml=(root/'api-gateway/src/main/resources'/yml_name).read_text()
    for token in ['redis-retry-min-ms:', 'redis-retry-max-ms:']:
        if token not in yml:
            errors.append(f'api-gateway {yml_name} portal-live recovery configuration missing {token}')

# Portal OIDC authorization must not use switchIfEmpty after a successful Mono<Void> downstream chain.
# A successful GatewayFilterChain returns Mono<Void> (no emitted item), so such a fallback
# would falsely turn registered portal users/SSE connections into 401/403 responses.
_portal_filter_path=root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/portal/PortalTokenFilter.java'
_portal_filter=_portal_filter_path.read_text()
if '.switchIfEmpty(reject(' in _portal_filter:
    errors.append('PortalTokenFilter must not use switchIfEmpty(reject(...)) on a pipeline that can complete as Mono<Void>')
for token in ['map(Optional::of)', 'defaultIfEmpty(Optional.empty())', 'PORTAL_USER_NOT_REGISTERED', 'PORTAL_AUTH_REQUIRED']:
    if token not in _portal_filter:
        errors.append(f'PortalTokenFilter explicit empty-state authorization handling missing {token}')
_portal_filter_test=(root/'api-gateway/src/test/java/com/paymentgateway/gateway/security/portal/PortalTokenFilterTest.java').read_text()
for token in ['registeredAdminSseRequestPassesThroughWithoutFalseEmptyRejection', 'unknownOidcSubjectIsRejectedAsNotRegistered', 'missingPortalAuthenticationIsRejectedAsAuthRequired']:
    if token not in _portal_filter_test:
        errors.append(f'PortalTokenFilter regression tests missing {token}')

# Portal route ownership regression: later auth filters must not strip trusted portal headers.
_public=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/publictoken/PublicClientTokenFilter.java').read_text()
_signature=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/security/ClientRequestSignatureFilter.java').read_text()
for name,text in [('PublicClientTokenFilter',_public),('ClientRequestSignatureFilter',_signature)]:
    if 'path.startsWith("/api/v1/portal/")' not in text or 'return chain.filter(exchange);' not in text:
        errors.append(f'{name} must preserve gateway-owned headers for /api/v1/portal/**')


# Portal OIDC session lifecycle / refresh-token regression checks.
for portal in ['admin-portal', 'merchant-portal']:
    auth_file = root/f'frontend/angular-workspace/projects/{portal}/src/app/core/auth/oidc-auth.service.ts'
    interceptor_file = root/f'frontend/angular-workspace/projects/{portal}/src/app/core/auth/auth.interceptor.ts'
    live_file = root/f'frontend/angular-workspace/projects/{portal}/src/app/core/live/portal-live.service.ts'
    auth_text = auth_file.read_text()
    interceptor_text = interceptor_file.read_text()
    live_text = live_file.read_text()
    for token in ["grant_type: 'refresh_token'", 'refreshAccessToken', 'validToken', 'OIDC ID token nonce validation failed', 'code_challenge_method: \'S256\'']:
        if token not in auth_text: errors.append(f'{portal}: OIDC lifecycle missing {token}')
    if "sessionStorage.setItem('portal_access_token'" in auth_text or 'sessionStorage.setItem(this.accessTokenKey' in auth_text:
        errors.append(f'{portal}: bearer access token must not be persisted in browser storage')
    if "sessionStorage.setItem('portal_refresh_token'" in auth_text or 'sessionStorage.setItem(this.refreshTokenKey' in auth_text:
        errors.append(f'{portal}: bearer refresh token must not be persisted in browser storage')
    for token in ['AUTH_RETRY', 'refreshAccessToken(true)', 'status !== 401']:
        if token not in interceptor_text: errors.append(f'{portal}: HTTP 401 refresh/retry interceptor missing {token}')
    for token in ['validToken(45)', 'response.status === 401', 'refreshAccessToken(true)']:
        if token not in live_text: errors.append(f'{portal}: authenticated SSE refresh handling missing {token}')

realm = json.loads((root/'deploy/keycloak/payment-gateway-realm.json').read_text())
if realm.get('accessTokenLifespan') != 300:
    errors.append('Keycloak realm should use explicit 5-minute access-token lifespan for refresh testing/security')
if realm.get('revokeRefreshToken') is not True or realm.get('refreshTokenMaxReuse') != 0:
    errors.append('Keycloak realm should rotate/revoke used refresh tokens')
for client_id in ['payment-admin-portal','payment-merchant-portal']:
    client = next((c for c in realm.get('clients',[]) if c.get('clientId') == client_id), None)
    if not client: errors.append(f'Keycloak realm missing {client_id}')
    elif client.get('fullScopeAllowed') is not False:
        errors.append(f'{client_id}: fullScopeAllowed must be false')

for token in ['rmqnamesrv:', 'rmqbroker:', 'condition: service_healthy', '/dev/tcp/127.0.0.1/9876', '/dev/tcp/127.0.0.1/10911']:
    if token not in compose: errors.append(f'Compose readiness hardening missing {token}')
for script_name in ['dev-stack.ps1','dev-stack.sh']:
    script_text=(root/'scripts'/script_name).read_text()
    for token in ['verify', 'Keycloak merchant-user provisioning client', 'rocketmq-init']:
        if token not in script_text: errors.append(f'{script_name}: stack verification missing {token}')

# Windows PowerShell must not use a failing native command as the normal
# Keycloak-volume existence probe while ErrorActionPreference is Stop.
ps_stack=(root/'scripts/dev-stack.ps1').read_text()
if 'docker volume inspect $volume' in ps_stack:
    errors.append('dev-stack.ps1 must not probe an absent Keycloak volume with docker volume inspect')
for token in ['Test-DockerVolumeExists', "docker volume ls --format '{{.Name}}'", 'Invoke-BestEffort']:
    if token not in ps_stack: errors.append(f'dev-stack.ps1 Keycloak volume PowerShell safety missing {token}')
for token in ['Repair-KeycloakDevCredentials', 'bootstrap-admin service', '/client-secret', '/reset-password', '--force-recreate payment-api-service']:
    if token not in ps_stack: errors.append(f'dev-stack.ps1 Keycloak credential reconciliation missing {token}')

# Admin transaction operations: server-side pagination/search and per-payment webhook drill-down.
transactions_ui=(root/'frontend/angular-workspace/projects/admin-portal/src/app/features/transactions/transactions.component.ts').read_text()
portal_api=(root/'frontend/angular-workspace/projects/admin-portal/src/app/core/http/portal-api.service.ts').read_text()
for token in ['transactionsPage(', 'paymentWebhooks(', 'Webhook', 'Showing', 'Payment status', 'webhookFilter', 'pageSize']:
    if token not in transactions_ui and token not in portal_api:
        errors.append(f'admin transactions pagination/webhook UI missing {token}')
for token in ['/admin/transactions/page', 'webhook_retrying', 'merchant_order_id ILIKE', 'LIMIT ? OFFSET ?']:
    if token not in portal_controller:
        errors.append(f'admin transactions server-side search/pagination missing {token}')

# Live portal totals must update when a payment is created, even before a later provider status transition.
core_persistence=(root/'payment-core-service/src/main/java/com/paymentgateway/core/service/PaymentPersistenceService.java').read_text()
outbox_publisher=(root/'notification-service/src/main/java/com/paymentgateway/notification/RocketMqOutboxPublisher.java').read_text()
rocket_consumer=(root/'notification-service/src/main/java/com/paymentgateway/notification/RocketMqClientManager.java').read_text()
for token in ['PAYMENT_CREATED', 'emitPaymentCreated(payment)', 'OutboxEventEntity.pending(p.getPaymentId(), PAYMENT_CREATED, payload)']:
    if token not in core_persistence: errors.append(f'payment creation live event missing {token}')
if "event_type IN ('PAYMENT_STATUS_CHANGED','PAYMENT_CREATED')" not in outbox_publisher:
    errors.append('outbox relay must claim PAYMENT_CREATED and PAYMENT_STATUS_CHANGED events')
for token in ['ObjectProvider<PortalLiveEventPublisher>', 'publisher.publish(envelope)', 'rocketMq.send(']:
    if token not in outbox_publisher: errors.append(f'direct committed-outbox portal live path missing {token}')
if outbox_publisher.find('publisher.publish(envelope)') > outbox_publisher.find('rocketMq.send('):
    errors.append('portal live Redis/SSE publication must occur before RocketMQ send')
if 'publisher.publish(event)' in rocket_consumer:
    errors.append('RocketMQ consumer must not be the portal SSE publication dependency')
for token in ['"PAYMENT_CREATED".equals(event.eventType())', 'handler.handle(event, message.getMessageId().toString())']:
    if token not in rocket_consumer: errors.append(f'RocketMQ durable consumer handling missing {token}')
admin_dashboard=(root/'frontend/angular-workspace/projects/admin-portal/src/app/features/dashboard/dashboard.component.ts').read_text()
for token in ['lastLiveEvent', 'lastLiveEventAt', 'Last event {{lastLiveEvent.type}}']:
    if token not in admin_dashboard: errors.append(f'admin dashboard live diagnostics missing {token}')
for token in ['Payment activity', 'Provider activity', 'dashboardActivity(24)', 'activityHourly', 'activityProviders']:
    if token not in admin_dashboard: errors.append(f'admin dashboard active payment charts missing {token}')
for token in ['/admin/dashboard/activity', 'generate_series(', 'selected_provider_code', 'failed_or_expired']:
    if token not in portal_controller: errors.append(f'admin dashboard activity API missing {token}')


# Portal SSE must terminate directly in api-gateway so browser live updates do not depend
# on proxying a long-lived MVC SseEmitter stream through Spring Cloud Gateway.
gateway_live_controller=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/portal/live/PortalLiveSseController.java').read_text()
gateway_live_stream=(root/'api-gateway/src/main/java/com/paymentgateway/gateway/portal/live/PortalLiveRedisStream.java').read_text()
gateway_config=(root/'api-gateway/src/main/resources/application.yml').read_text()
for token in ['id: portal-live-sse', 'forward:///internal/portal/live/events', 'Path=/api/v1/portal/live/events']:
    if token not in gateway_config: errors.append(f'gateway-owned portal SSE route missing {token}')
for token in ['/internal/portal/live/events', 'ServerSentEvent', 'AUTH_MODE_PORTAL', 'payment-status', 'heartbeat']:
    if token not in gateway_live_controller: errors.append(f'gateway portal SSE controller missing {token}')
for token in ['ReactiveRedisMessageListenerContainer', 'container.receive(topic)', 'txnhub.portal.payment-events', 'Flux.defer', 'Retry.backoff']:
    if token not in gateway_live_stream: errors.append(f'gateway Redis live stream missing {token}')
for stale in ['.publish()', '.refCount(']:
    if stale in gateway_live_stream: errors.append(f'gateway Redis live stream must not use stale lifecycle token {stale}')
admin_live=(root/'frontend/angular-workspace/projects/admin-portal/src/app/core/live/portal-live.service.ts').read_text()
for token in ['readonly error$', 'Live event stream failed with HTTP', 'errorSubject.next']:
    if token not in admin_live: errors.append(f'admin SSE diagnostics missing {token}')

# Centralized exception-handling / external error-contract regression checks.
_api_error = root/'payment-api-service/src/main/java/com/paymentgateway/api/web/ApiErrorResponse.java'
_api_handler = root/'payment-api-service/src/main/java/com/paymentgateway/api/web/ApiExceptionHandler.java'
if not _api_error.exists(): errors.append('payment-api-service missing ApiErrorResponse')
if not _api_handler.exists():
    errors.append('payment-api-service missing centralized ApiExceptionHandler')
else:
    text=_api_handler.read_text()
    for token in ['X-Correlation-Id','VALIDATION_ERROR','PAYMENT_NOT_FOUND','IDEMPOTENCY_CONFLICT',
                  'INVALID_PAYMENT_STATE','UNSUPPORTED_PROVIDER_CAPABILITY','PROVIDER_UNAVAILABLE',
                  'UPSTREAM_UNAVAILABLE','UPSTREAM_TIMEOUT','INTERNAL_ERROR','@ExceptionHandler(Exception.class)']:
        if token not in text: errors.append(f'ApiExceptionHandler missing stable error handling token {token}')
_contract_error_dir=root/'payment-contract/src/main/java/com/paymentgateway/contract/error'
for name in ['PaymentGatewayException.java','PaymentNotFoundException.java','IdempotencyConflictException.java',
             'InvalidPaymentStateException.java','ProviderCapabilityException.java','ProviderNotAvailableException.java',
             'ProviderUnavailableException.java','ProviderOperationRejectedException.java']:
    if not (_contract_error_dir/name).exists(): errors.append(f'payment-contract missing shared exception {name}')
for stale in ['IdempotencyConflictException.java','PaymentNotFoundException.java','ProviderCapabilityException.java']:
    if (root/'payment-core-service/src/main/java/com/paymentgateway/core/service'/stale).exists():
        errors.append(f'payment-core must use shared payment-contract exception instead of local {stale}')
_callback_handler=root/'callback-service/src/main/java/com/paymentgateway/callback/CallbackExceptionHandler.java'
if not _callback_handler.exists(): errors.append('callback-service missing sanitized exception boundary')
_sdk_exception=root/'sdk/java/src/main/java/com/txnhub/sdk/TxnHubException.java'
if _sdk_exception.exists():
    sdk_text=_sdk_exception.read_text()
    for token in ['errorCode()', 'correlationId()', 'apiMessage()']:
        if token not in sdk_text: errors.append(f'TxnHubException structured API error parsing missing {token}')

# Spring Framework 7 HTTP 422 naming and project-wide JavaDoc quality guards.
_java_sources = list(root.rglob('*.java'))
for java_file in _java_sources:
    java_text = java_file.read_text(errors='ignore')
    if 'HttpStatus.UNPROCESSABLE_ENTITY' in java_text:
        errors.append(f'{java_file.relative_to(root)} uses deprecated Spring 7 HttpStatus.UNPROCESSABLE_ENTITY; use UNPROCESSABLE_CONTENT')
if _api_handler.exists():
    handler_text = _api_handler.read_text()
    for token in ['HttpStatus.UNPROCESSABLE_CONTENT',
                  'Maps a shared payment-domain exception to the stable external API error contract.',
                  'Final exception boundary for failures not matched by a more specific handler.',
                  'Builds the common API error envelope and logs the failure at the appropriate severity.']:
        if token not in handler_text:
            errors.append(f'ApiExceptionHandler method JavaDoc/status modernization missing {token}')
if _callback_handler.exists():
    callback_text = _callback_handler.read_text()
    for token in ['Maps shared payment-domain failures raised while processing provider callbacks.',
                  'Final callback exception boundary that translates known upstream failures and hides internals.',
                  'Builds the callback error envelope and logs the original exception server-side.']:
        if token not in callback_text:
            errors.append(f'CallbackExceptionHandler method JavaDoc missing {token}')
if _sdk_exception.exists():
    sdk_text = _sdk_exception.read_text()
    for token in ['@return stable TXN HUB API error code', 'Parses the structured TXN HUB error envelope']:
        if token not in sdk_text:
            errors.append(f'TxnHubException method JavaDoc missing {token}')


_javadoc_checker=root/'scripts/quality/JavaDocCoverageCheck.java'
if not _javadoc_checker.exists():
    errors.append('missing project-wide JavaDocCoverageCheck utility')
else:
    checker_text=_javadoc_checker.read_text()
    for token in ['DocTrees.instance(task)', 'visitClass(ClassTree node', 'visitMethod(MethodTree node', 'JavaDoc coverage PASS']:
        if token not in checker_text:
            errors.append(f'JavaDocCoverageCheck implementation missing {token}')

if errors:
    print('\n'.join(errors)); sys.exit(1)
print('Source structure checks passed.')
