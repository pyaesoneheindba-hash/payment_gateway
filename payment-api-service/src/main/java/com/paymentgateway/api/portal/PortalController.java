package com.paymentgateway.api.portal;

import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paymentgateway.api.portal.MerchantOnboardingService.MerchantOnboardingConflictException;
import com.paymentgateway.api.webhook.WebhookUrlPolicy;
import com.paymentgateway.contract.payment.PaymentRpcService;

/**
 * Exposes HTTP endpoints for portal operations.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {
    private static final String ROLE_HEADER = "X-Payment-Gateway-Portal-Role";

    private final JdbcTemplate jdbc;
    private final PortalAuthorization authorization;
    private final WebhookUrlPolicy webhookUrlPolicy;
    private final RedirectUrlPolicy redirectUrlPolicy;
    private final MerchantOnboardingService merchantOnboarding;
    private final MerchantPortalLoginRepairService merchantPortalLoginRepair;

    @DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
    private PaymentRpcService paymentRpcService;

    /**
     * Creates a new {@code PortalController} instance.
     *
     * @param jdbc                      the jdbc value
     * @param authorization             the authorization value
     * @param webhookUrlPolicy          the webhook URL policy value
     * @param redirectUrlPolicy         the redirect URL policy value
     * @param merchantOnboarding        the merchant onboarding value
     * @param merchantPortalLoginRepair the merchant portal login repair value
     */
    public PortalController(JdbcTemplate jdbc, PortalAuthorization authorization,
            WebhookUrlPolicy webhookUrlPolicy, RedirectUrlPolicy redirectUrlPolicy,
            MerchantOnboardingService merchantOnboarding, MerchantPortalLoginRepairService merchantPortalLoginRepair) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.webhookUrlPolicy = webhookUrlPolicy;
        this.redirectUrlPolicy = redirectUrlPolicy;
        this.merchantOnboarding = merchantOnboarding;
        this.merchantPortalLoginRepair = merchantPortalLoginRepair;
    }

    /**
     * Executes the me operation.
     *
     * @param role           the authenticated portal role
     * @param portalUserId   the portal user identifier identifier
     * @param merchantId     the merchant identifier
     * @param merchantStatus the merchant status value
     * @return the resulting key-value data
     */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(ROLE_HEADER) String role,
            @RequestHeader("X-Payment-Gateway-Portal-User-Id") UUID portalUserId,
            @RequestHeader(value = "X-Merchant-Id", required = false) UUID merchantId,
            @RequestHeader(value = "X-Payment-Gateway-Merchant-Status", required = false) String merchantStatus) {
        if (role.startsWith("MERCHANT_"))
            authorization.requireMerchantRead(role);
        else
            authorization.requireAdminRead(role);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("portalUserId", portalUserId);
        result.put("role", role);
        if (merchantId != null)
            result.put("merchantId", merchantId);
        if (merchantStatus != null)
            result.put("merchantStatus", merchantStatus);
        return result;
    }

    /**
     * Executes the merchant summary operation.
     *
     * @param merchantId the merchant identifier
     * @param role       the authenticated portal role
     * @return the resulting key-value data
     */
    @GetMapping("/merchant/summary")
    public Map<String, Object> merchantSummary(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(ROLE_HEADER) String role) {
        authorization.requireMerchantRead(role);
        return jdbc.queryForMap(
                """
                        SELECT count(*) AS total_transactions,
                               count(*) FILTER (WHERE status='SUCCEEDED') AS succeeded,
                               count(*) FILTER (WHERE status IN ('FAILED','EXPIRED','VOIDED')) AS failed_or_expired,
                               count(*) FILTER (WHERE status IN ('PENDING','QR_GENERATED','ACTION_REQUIRED','UNKNOWN')) AS pending,
                               COALESCE(sum(amount_minor) FILTER (WHERE status='SUCCEEDED'),0) AS succeeded_amount_minor
                        FROM payment WHERE merchant_id=?
                        """,
                merchantId);
    }

    /**
     * Executes the merchant transactions operation.
     *
     * @param merchantId the merchant identifier
     * @param role       the authenticated portal role
     * @param status     the payment or delivery status
     * @param limit      the limit value
     * @return the matching values
     */
    @GetMapping("/merchant/transactions")
    public List<Map<String, Object>> merchantTransactions(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(ROLE_HEADER) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        authorization.requireMerchantRead(role);
        int safe = Math.max(1, Math.min(limit, 200));
        if (status == null || status.isBlank())
            return jdbc.queryForList("""
                    SELECT payment_id,merchant_order_id,amount_minor,currency,status,selected_provider_code,
                           provider_order_id,provider_reference_id,presentation_mode,expires_at,created_at,updated_at
                    FROM payment WHERE merchant_id=? ORDER BY created_at DESC LIMIT ?
                    """, merchantId, safe);
        return jdbc.queryForList("""
                SELECT payment_id,merchant_order_id,amount_minor,currency,status,selected_provider_code,
                       provider_order_id,provider_reference_id,presentation_mode,expires_at,created_at,updated_at
                FROM payment WHERE merchant_id=? AND status=? ORDER BY created_at DESC LIMIT ?
                """, merchantId, status.toUpperCase(Locale.ROOT), safe);
    }

    /**
     * Executes the reconcile operation.
     *
     * @param merchantId the merchant identifier
     * @param role       the authenticated portal role
     * @param paymentId  the payment identifier
     * @return the resulting {@code Object} value
     */
    @PostMapping("/merchant/transactions/{paymentId}/reconcile")
    public Object reconcile(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(ROLE_HEADER) String role, @PathVariable UUID paymentId) {
        authorization.requireMerchantReconciliation(role);
        return paymentRpcService.reconcilePayment(merchantId, paymentId);
    }

    /**
     * Executes the merchant keys operation.
     *
     * @param merchantId the merchant identifier
     * @param role       the authenticated portal role
     * @return the matching values
     */
    @GetMapping("/merchant/api-keys")
    public List<Map<String, Object>> merchantKeys(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(ROLE_HEADER) String role) {
        authorization.requireMerchantRead(role);
        return jdbc.queryForList(
                "SELECT key_id,algorithm,status,valid_from,valid_until,created_at FROM merchant_api_key WHERE merchant_id=? ORDER BY created_at DESC",
                merchantId);
    }

    /**
     * Immutable data carrier for key registration.
     */
    public record KeyRegistration(String keyId, String publicKey, String validUntil) {
    }

    /**
     * Registers key.
     *
     * @param merchantId the merchant identifier
     * @param role       the authenticated portal role
     * @param request    the current request or request payload
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PostMapping("/merchant/api-keys")
    public ResponseEntity<?> registerKey(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(ROLE_HEADER) String role, @RequestBody KeyRegistration request) {
        authorization.requireMerchantKeyAdmin(role);
        if (request == null || request.keyId() == null || !request.keyId().matches("[A-Za-z0-9._:-]{1,100}")
                || request.publicKey() == null || request.publicKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_KEY"));
        }
        Instant validUntil = null;
        if (request.validUntil() != null && !request.validUntil().isBlank()) {
            try {
                validUntil = Instant.parse(request.validUntil());
            } catch (RuntimeException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "INVALID_VALID_UNTIL"));
            }
            if (!validUntil.isAfter(Instant.now()))
                return ResponseEntity.badRequest().body(Map.of("error", "VALID_UNTIL_MUST_BE_FUTURE"));
        }
        final String normalizedPublicKey = request.publicKey().trim();
        try {
            validateEd25519PublicKey(normalizedPublicKey);
            jdbc.update(
                    "INSERT INTO merchant_api_key(key_id,merchant_id,algorithm,public_key,status,valid_from,valid_until) VALUES (?,?,'ED25519',?,'ACTIVE',now(),?)",
                    request.keyId(), merchantId, normalizedPublicKey,
                    validUntil == null ? null : java.sql.Timestamp.from(validUntil));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_PUBLIC_KEY", "message", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "KEY_ID_ALREADY_EXISTS"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "keyId", request.keyId(), "merchantId", merchantId, "status", "ACTIVE"));
    }

    /**
     * Validates ed25519 public key.
     *
     * @param pem the PEM value
     */
    private static void validateEd25519PublicKey(String pem) {
        try {
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(encoded);
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("publicKey must be a valid Ed25519 X.509 PEM public key", e);
        }
    }

    /**
     * Executes the merchant webhooks operation.
     *
     * @param merchantId the merchant identifier
     * @param role       the authenticated portal role
     * @return the matching values
     */
    @GetMapping("/merchant/webhooks")
    public List<Map<String, Object>> merchantWebhooks(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(ROLE_HEADER) String role) {
        authorization.requireMerchantRead(role);
        return jdbc.queryForList(
                "SELECT endpoint_id,event_type,url,description,status,created_at,updated_at FROM merchant_webhook_endpoint WHERE merchant_id=? ORDER BY created_at DESC",
                merchantId);
    }

    /**
     * Immutable data carrier for webhook registration.
     */
    public record WebhookRegistration(String url, String description) {
    }

    /**
     * Adds merchant webhook.
     *
     * @param merchantId the merchant identifier
     * @param role       the authenticated portal role
     * @param request    the current request or request payload
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PostMapping("/merchant/webhooks")
    public ResponseEntity<?> addMerchantWebhook(@RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(ROLE_HEADER) String role, @RequestBody WebhookRegistration request) {
        authorization.requireMerchantWebhookAdmin(role);
        if (request == null || request.url() == null)
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_WEBHOOK_URL"));
        final String normalized;
        try {
            normalized = webhookUrlPolicy.validateAndNormalize(request.url());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_WEBHOOK_URL", "message", e.getMessage()));
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO merchant_webhook_endpoint(endpoint_id,merchant_id,event_type,url,description,status) VALUES (?,?,'PAYMENT_STATUS_CHANGED',?,?,'ACTIVE')",
                id, merchantId, normalized, request.description());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("endpointId", id, "url", normalized, "status", "ACTIVE"));
    }

    /**
     * Executes the admin summary operation.
     *
     * @param role the authenticated portal role
     * @return the resulting key-value data
     */
    @GetMapping("/admin/summary")
    public Map<String, Object> adminSummary(@RequestHeader(ROLE_HEADER) String role) {
        authorization.requireAdminRead(role);
        Map<String, Object> out = new LinkedHashMap<>();
        long payments = jdbc.queryForObject("SELECT count(*) FROM payment", Long.class);
        long succeeded = jdbc.queryForObject("SELECT count(*) FROM payment WHERE status='SUCCEEDED'", Long.class);
        long pending = jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE status IN ('PENDING','QR_GENERATED','ACTION_REQUIRED','UNKNOWN')",
                Long.class);
        long failedOrExpired = jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE status IN ('FAILED','EXPIRED','VOIDED')", Long.class);
        out.put("merchants", jdbc.queryForObject("SELECT count(*) FROM merchant", Long.class));
        out.put("payments", payments);
        out.put("succeeded", succeeded);
        out.put("pending", pending);
        out.put("failedOrExpired", failedOrExpired);
        out.put("other", Math.max(0L, payments - succeeded - pending - failedOrExpired));

        // Webhook delivery rows are delivery attempts/events, not payment rows. Keep
        // these metrics separate so the dashboard never implies that a delivery
        // count is a payment count.
        out.put("webhookTotal", jdbc.queryForObject("SELECT count(*) FROM webhook_delivery", Long.class));
        out.put("webhookDelivered",
                jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE status='DELIVERED'", Long.class));
        out.put("webhookPending", jdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery WHERE status IN ('PENDING','SENDING')", Long.class));
        out.put("webhookRetrying",
                jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE status='RETRY'", Long.class));
        out.put("webhookDead",
                jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE status='DEAD'", Long.class));
        out.put("paymentsWithWebhookIssues",
                jdbc.queryForObject(
                        "SELECT count(DISTINCT payment_id) FROM webhook_delivery WHERE status IN ('RETRY','DEAD')",
                        Long.class));
        // Compatibility field retained for older portal builds.
        out.put("webhookFailures", jdbc
                .queryForObject("SELECT count(*) FROM webhook_delivery WHERE status IN ('RETRY','DEAD')", Long.class));
        return out;
    }

    /**
     * Executes the admin dashboard activity operation.
     *
     * @param role  the authenticated portal role
     * @param hours the hours value
     * @return the resulting key-value data
     */
    @GetMapping("/admin/dashboard/activity")
    public Map<String, Object> adminDashboardActivity(@RequestHeader(ROLE_HEADER) String role,
            @RequestParam(defaultValue = "24") int hours) {
        authorization.requireAdminRead(role);
        int safeHours = Math.max(6, Math.min(hours, 168));
        Instant from = Instant.now().minusSeconds((long) (safeHours - 1) * 3600L);

        List<Map<String, Object>> hourly = jdbc.queryForList(
                """
                        WITH buckets AS (
                            SELECT generate_series(
                                date_trunc('hour', CAST(? AS timestamptz)),
                                date_trunc('hour', now()),
                                interval '1 hour'
                            ) AS bucket
                        )
                        SELECT b.bucket,
                               count(p.payment_id) AS total,
                               count(p.payment_id) FILTER (WHERE p.status='SUCCEEDED') AS succeeded,
                               count(p.payment_id) FILTER (WHERE p.status IN ('PENDING','QR_GENERATED','ACTION_REQUIRED','UNKNOWN')) AS in_progress,
                               count(p.payment_id) FILTER (WHERE p.status IN ('FAILED','EXPIRED','VOIDED')) AS failed_or_expired,
                               count(p.payment_id) FILTER (WHERE p.status NOT IN ('SUCCEEDED','PENDING','QR_GENERATED','ACTION_REQUIRED','UNKNOWN','FAILED','EXPIRED','VOIDED')) AS other
                        FROM buckets b
                        LEFT JOIN payment p ON p.created_at >= b.bucket AND p.created_at < b.bucket + interval '1 hour'
                        GROUP BY b.bucket
                        ORDER BY b.bucket
                        """,
                java.sql.Timestamp.from(from));

        List<Map<String, Object>> providers = jdbc.queryForList(
                """
                        SELECT COALESCE(NULLIF(selected_provider_code,''),'UNASSIGNED') AS provider,
                               count(*) AS total,
                               count(*) FILTER (WHERE status='SUCCEEDED') AS succeeded,
                               count(*) FILTER (WHERE status IN ('PENDING','QR_GENERATED','ACTION_REQUIRED','UNKNOWN')) AS in_progress,
                               count(*) FILTER (WHERE status IN ('FAILED','EXPIRED','VOIDED')) AS failed_or_expired,
                               count(*) FILTER (WHERE status NOT IN ('SUCCEEDED','PENDING','QR_GENERATED','ACTION_REQUIRED','UNKNOWN','FAILED','EXPIRED','VOIDED')) AS other
                        FROM payment
                        WHERE created_at >= ?
                        GROUP BY COALESCE(NULLIF(selected_provider_code,''),'UNASSIGNED')
                        ORDER BY count(*) DESC, provider
                        LIMIT 8
                        """,
                java.sql.Timestamp.from(from));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowHours", safeHours);
        out.put("generatedAt", Instant.now());
        out.put("hourly", hourly);
        out.put("providers", providers);
        return out;
    }

    /**
     * Executes the admin transactions operation.
     *
     * @param role   the authenticated portal role
     * @param status the payment or delivery status
     * @param limit  the limit value
     * @return the matching values
     */
    @GetMapping("/admin/transactions")
    public List<Map<String, Object>> adminTransactions(@RequestHeader(ROLE_HEADER) String role,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        authorization.requireAdminRead(role);
        int safe = Math.max(1, Math.min(limit, 500));
        String select = """
                SELECT p.payment_id,p.merchant_id,p.merchant_order_id,p.amount_minor,p.currency,p.status,p.selected_provider_code,
                       p.provider_order_id,p.presentation_mode,p.expires_at,p.created_at,p.updated_at,
                       COALESCE(w.webhook_total,0) AS webhook_total,
                       COALESCE(w.webhook_delivered,0) AS webhook_delivered,
                       COALESCE(w.webhook_pending,0) AS webhook_pending,
                       COALESCE(w.webhook_retrying,0) AS webhook_retrying,
                       COALESCE(w.webhook_dead,0) AS webhook_dead,
                       w.webhook_last_updated
                FROM payment p
                LEFT JOIN (
                    SELECT payment_id,
                           count(*) AS webhook_total,
                           count(*) FILTER (WHERE status='DELIVERED') AS webhook_delivered,
                           count(*) FILTER (WHERE status IN ('PENDING','SENDING')) AS webhook_pending,
                           count(*) FILTER (WHERE status='RETRY') AS webhook_retrying,
                           count(*) FILTER (WHERE status='DEAD') AS webhook_dead,
                           max(updated_at) AS webhook_last_updated
                    FROM webhook_delivery
                    GROUP BY payment_id
                ) w ON w.payment_id=p.payment_id
                """;
        if (status == null || status.isBlank()) {
            return jdbc.queryForList(select + " ORDER BY p.created_at DESC LIMIT ?", safe);
        }
        return jdbc.queryForList(select + " WHERE p.status=? ORDER BY p.created_at DESC LIMIT ?",
                status.toUpperCase(Locale.ROOT), safe);
    }

    /**
     * Executes the admin transactions page operation.
     *
     * @param role        the authenticated portal role
     * @param page        the page value
     * @param size        the size value
     * @param search      the search value
     * @param merchant    the merchant value
     * @param orderId     the provider or merchant order identifier
     * @param paymentId   the payment identifier
     * @param amountMinor the payment amount in minor currency units
     * @param currency    the ISO currency code
     * @param provider    the provider value
     * @param status      the payment or delivery status
     * @param webhook     the webhook value
     * @param createdFrom the created from value
     * @param createdTo   the created to value
     * @return the resulting key-value data
     */
    @GetMapping("/admin/transactions/page")
    public Map<String, Object> adminTransactionsPage(@RequestHeader(ROLE_HEADER) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String merchant,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String paymentId,
            @RequestParam(required = false) Long amountMinor,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String webhook,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo) {
        authorization.requireAdminRead(role);
        int safeSize = Math.max(10, Math.min(size, 100));
        int requestedPage = Math.max(0, page);

        String from = """
                FROM payment p
                JOIN merchant m ON m.merchant_id=p.merchant_id
                LEFT JOIN (
                    SELECT payment_id,
                           count(*) AS webhook_total,
                           count(*) FILTER (WHERE status='DELIVERED') AS webhook_delivered,
                           count(*) FILTER (WHERE status IN ('PENDING','SENDING')) AS webhook_pending,
                           count(*) FILTER (WHERE status='RETRY') AS webhook_retrying,
                           count(*) FILTER (WHERE status='DEAD') AS webhook_dead,
                           max(updated_at) AS webhook_last_updated
                    FROM webhook_delivery
                    GROUP BY payment_id
                ) w ON w.payment_id=p.payment_id
                """;

        List<String> conditions = new java.util.ArrayList<>();
        List<Object> params = new java.util.ArrayList<>();

        // Backward-compatible global search. The admin UI now primarily uses the
        // explicit column filters below so users can see exactly what is filtered.
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim() + "%";
            conditions.add(
                    "(CAST(p.payment_id AS text) ILIKE ? OR CAST(p.merchant_id AS text) ILIKE ? OR p.merchant_order_id ILIKE ? OR COALESCE(p.selected_provider_code,'') ILIKE ? OR m.merchant_code ILIKE ? OR m.merchant_name ILIKE ?)");
            for (int i = 0; i < 6; i++)
                params.add(pattern);
        }
        if (merchant != null && !merchant.isBlank()) {
            String pattern = "%" + merchant.trim() + "%";
            conditions
                    .add("(CAST(p.merchant_id AS text) ILIKE ? OR m.merchant_code ILIKE ? OR m.merchant_name ILIKE ?)");
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        if (orderId != null && !orderId.isBlank()) {
            conditions.add("p.merchant_order_id ILIKE ?");
            params.add("%" + orderId.trim() + "%");
        }
        if (paymentId != null && !paymentId.isBlank()) {
            conditions.add("CAST(p.payment_id AS text) ILIKE ?");
            params.add("%" + paymentId.trim() + "%");
        }
        if (amountMinor != null) {
            conditions.add("p.amount_minor=?");
            params.add(amountMinor);
        }
        if (currency != null && !currency.isBlank()) {
            conditions.add("UPPER(p.currency)=?");
            params.add(currency.trim().toUpperCase(Locale.ROOT));
        }
        if (provider != null && !provider.isBlank()) {
            conditions.add("COALESCE(p.selected_provider_code,'') ILIKE ?");
            params.add("%" + provider.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            conditions.add("p.status=?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (webhook != null && !webhook.isBlank()) {
            switch (webhook.trim().toUpperCase(Locale.ROOT)) {
                case "ISSUES" -> conditions.add("(COALESCE(w.webhook_retrying,0)>0 OR COALESCE(w.webhook_dead,0)>0)");
                case "RETRY" -> conditions.add("COALESCE(w.webhook_retrying,0)>0");
                case "DEAD" -> conditions.add("COALESCE(w.webhook_dead,0)>0");
                case "DELIVERED" -> conditions.add("COALESCE(w.webhook_delivered,0)>0");
                case "PENDING" -> conditions.add("COALESCE(w.webhook_pending,0)>0");
                case "NONE" -> conditions.add("COALESCE(w.webhook_total,0)=0");
                default -> {
                    /* ALL / unknown values intentionally add no condition. */ }
            }
        }
        if (createdFrom != null && !createdFrom.isBlank()) {
            conditions.add("p.created_at >= CAST(? AS date)");
            params.add(createdFrom.trim());
        }
        if (createdTo != null && !createdTo.isBlank()) {
            conditions.add("p.created_at < (CAST(? AS date) + INTERVAL '1 day')");
            params.add(createdTo.trim());
        }

        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        Long totalValue = jdbc.queryForObject("SELECT count(*) " + from + where, Long.class, params.toArray());
        long total = totalValue == null ? 0L : totalValue;
        int totalPages = total == 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
        int effectivePage = totalPages == 0 ? 0 : Math.min(requestedPage, totalPages - 1);
        int offset = effectivePage * safeSize;

        String select = """
                SELECT p.payment_id,p.merchant_id,m.merchant_code,m.merchant_name,p.merchant_order_id,
                       p.amount_minor,p.currency,p.status,p.selected_provider_code,p.provider_order_id,
                       p.presentation_mode,p.expires_at,p.created_at,p.updated_at,
                       COALESCE(w.webhook_total,0) AS webhook_total,
                       COALESCE(w.webhook_delivered,0) AS webhook_delivered,
                       COALESCE(w.webhook_pending,0) AS webhook_pending,
                       COALESCE(w.webhook_retrying,0) AS webhook_retrying,
                       COALESCE(w.webhook_dead,0) AS webhook_dead,
                       w.webhook_last_updated
                """;
        List<Object> dataParams = new java.util.ArrayList<>(params);
        dataParams.add(safeSize);
        dataParams.add(offset);
        List<Map<String, Object>> items = jdbc.queryForList(
                select + from + where + " ORDER BY p.created_at DESC, p.payment_id DESC LIMIT ? OFFSET ?",
                dataParams.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", effectivePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("totalPages", totalPages);
        return result;
    }

    /**
     * Executes the admin payment webhooks operation.
     *
     * @param role      the authenticated portal role
     * @param paymentId the payment identifier
     * @return the matching values
     */
    @GetMapping("/admin/transactions/{paymentId}/webhooks")
    public List<Map<String, Object>> adminPaymentWebhooks(@RequestHeader(ROLE_HEADER) String role,
            @PathVariable("paymentId") UUID paymentId) {
        authorization.requireAdminRead(role);
        return jdbc.queryForList("""
                SELECT d.delivery_id,d.event_id,d.payment_id,d.event_type,d.status,d.attempt_count,
                       d.last_http_status,d.last_error,d.next_attempt_at,d.last_attempt_at,d.delivered_at,
                       d.created_at,d.updated_at,
                       e.endpoint_id,e.url AS endpoint_url,e.status AS endpoint_status,
                       o.payload->>'previousStatus' AS payment_previous_status,
                       o.payload->>'status' AS payment_event_status,
                       o.payload->>'source' AS event_source,
                       o.created_at AS event_occurred_at
                FROM webhook_delivery d
                JOIN merchant_webhook_endpoint e ON e.endpoint_id=d.endpoint_id
                JOIN outbox_event o ON o.event_id=d.event_id
                WHERE d.payment_id=?
                ORDER BY d.created_at DESC
                """, paymentId);
    }

    /**
     * Executes the admin reconcile operation.
     *
     * @param role       the authenticated portal role
     * @param merchantId the merchant identifier
     * @param paymentId  the payment identifier
     * @return the resulting {@code Object} value
     */
    @PostMapping("/admin/transactions/{paymentId}/reconcile")
    public Object adminReconcile(@RequestHeader(ROLE_HEADER) String role,
            @RequestParam UUID merchantId, @PathVariable UUID paymentId) {
        authorization.requireAdminReconciliation(role);
        return paymentRpcService.reconcilePayment(merchantId, paymentId);
    }

    /**
     * Executes the merchants operation.
     *
     * @param role the authenticated portal role
     * @return the matching values
     */
    @GetMapping("/admin/merchants")
    public List<Map<String, Object>> merchants(@RequestHeader(ROLE_HEADER) String role) {
        authorization.requireAdminRead(role);
        return jdbc.queryForList(
                """
                        SELECT m.merchant_id,m.merchant_code,m.merchant_name,m.status,m.environment,m.default_currency,
                               m.contact_email,m.created_at,
                               (SELECT pu.username FROM portal_user pu
                                WHERE pu.merchant_id=m.merchant_id AND pu.role='MERCHANT_ADMIN'
                                ORDER BY CASE WHEN pu.status='ACTIVE' THEN 0 ELSE 1 END,pu.created_at LIMIT 1) AS portal_admin_username,
                               (SELECT pu.display_name FROM portal_user pu
                                WHERE pu.merchant_id=m.merchant_id AND pu.role='MERCHANT_ADMIN'
                                ORDER BY CASE WHEN pu.status='ACTIVE' THEN 0 ELSE 1 END,pu.created_at LIMIT 1) AS portal_admin_display_name
                        FROM merchant m ORDER BY m.created_at DESC
                        """);
    }

    /**
     * Executes the onboard merchant operation.
     *
     * @param role    the authenticated portal role
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PostMapping("/admin/merchants/onboard")
    public ResponseEntity<?> onboardMerchant(@RequestHeader(ROLE_HEADER) String role,
            @RequestBody MerchantOnboardingService.OnboardingRequest request) {
        authorization.requireSuperAdmin(role);
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(merchantOnboarding.onboard(request));
        } catch (MerchantOnboardingConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "ONBOARDING_CONFLICT", "message", e.getMessage()));
        } catch (KeycloakPortalIdentityProvisioner.IdentityProvisioningException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "PORTAL_IDENTITY_PROVISIONING_FAILED",
                    "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_ONBOARDING_REQUEST", "message", e.getMessage()));
        }
    }

    /**
     * Executes the merchant readiness operation.
     *
     * @param role       the authenticated portal role
     * @param merchantId the merchant identifier
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @GetMapping("/admin/merchants/{merchantId}/readiness")
    public ResponseEntity<?> merchantReadiness(@RequestHeader(ROLE_HEADER) String role, @PathVariable UUID merchantId) {
        authorization.requireAdminRead(role);
        try {
            return ResponseEntity.ok(merchantOnboarding.readiness(merchantId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "MERCHANT_NOT_FOUND"));
        }
    }

    /**
     * Executes the activate merchant operation.
     *
     * @param role       the authenticated portal role
     * @param merchantId the merchant identifier
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PostMapping("/admin/merchants/{merchantId}/activate")
    public ResponseEntity<?> activateMerchant(@RequestHeader(ROLE_HEADER) String role, @PathVariable UUID merchantId) {
        authorization.requireSuperAdmin(role);
        try {
            return ResponseEntity.ok(merchantOnboarding.activate(merchantId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "MERCHANT_NOT_FOUND"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "MERCHANT_NOT_READY", "message", e.getMessage()));
        }
    }

    /**
     * Executes the suspend merchant operation.
     *
     * @param role       the authenticated portal role
     * @param merchantId the merchant identifier
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PostMapping("/admin/merchants/{merchantId}/suspend")
    public ResponseEntity<?> suspendMerchant(@RequestHeader(ROLE_HEADER) String role, @PathVariable UUID merchantId) {
        authorization.requireSuperAdmin(role);
        try {
            return ResponseEntity.ok(merchantOnboarding.suspend(merchantId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "MERCHANT_STATUS_UPDATE_FAILED", "message", e.getMessage()));
        }
    }

    /**
     * Executes the repair merchant portal login operation.
     *
     * @param role       the authenticated portal role
     * @param merchantId the merchant identifier
     * @param request    the current request or request payload
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PostMapping("/admin/merchants/{merchantId}/portal-login/repair")
    public ResponseEntity<?> repairMerchantPortalLogin(
            @RequestHeader(ROLE_HEADER) String role,
            @PathVariable UUID merchantId,
            @RequestBody MerchantPortalLoginRepairService.RepairRequest request) {
        authorization.requireSuperAdmin(role);
        try {
            return ResponseEntity.ok(merchantPortalLoginRepair.repair(merchantId, request));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "MERCHANT_NOT_FOUND"));
        } catch (MerchantPortalLoginRepairService.RepairConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "PORTAL_LOGIN_REPAIR_CONFLICT", "message", e.getMessage()));
        } catch (KeycloakPortalIdentityProvisioner.IdentityProvisioningException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "PORTAL_IDENTITY_REPAIR_FAILED", "message", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "INVALID_PORTAL_LOGIN_REPAIR", "message", e.getMessage()));
        }
    }

    /**
     * Immutable data carrier for create merchant.
     */
    public record CreateMerchant(String merchantCode, String merchantName, String environment, String contactEmail) {
    }

    /**
     * Creates merchant.
     *
     * @param role    the authenticated portal role
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PostMapping("/admin/merchants")
    public ResponseEntity<?> createMerchant(@RequestHeader(ROLE_HEADER) String role,
            @RequestBody CreateMerchant request) {
        authorization.requireSuperAdmin(role);
        if (request == null || request.merchantCode() == null || !request.merchantCode().matches("[A-Za-z0-9_-]{2,64}")
                || request.merchantName() == null || request.merchantName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_MERCHANT"));
        }
        UUID id = UUID.randomUUID();
        String env = request.environment() == null ? "UAT" : request.environment().toUpperCase(Locale.ROOT);
        if (!Set.of("UAT", "PRODUCTION").contains(env))
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_ENVIRONMENT"));
        jdbc.update(
                "INSERT INTO merchant(merchant_id,merchant_code,merchant_name,status,environment,default_currency,contact_email) VALUES (?,?,?,'PENDING',?,'MMK',?)",
                id, request.merchantCode(), request.merchantName(), env, request.contactEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "merchantId", id, "merchantCode", request.merchantCode(), "status", "PENDING"));
    }

    /**
     * Immutable data carrier for redirect registration.
     */
    public record RedirectRegistration(String redirectUrl) {
    }

    /**
     * Immutable data carrier for redirect update.
     */
    public record RedirectUpdate(String redirectUrl, String status) {
    }

    /**
     * Executes the merchant redirects operation.
     *
     * @param role       the authenticated portal role
     * @param merchantId the merchant identifier
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @GetMapping("/admin/merchants/{merchantId}/redirect-uris")
    public ResponseEntity<?> merchantRedirects(@RequestHeader(ROLE_HEADER) String role,
            @PathVariable UUID merchantId) {
        authorization.requireAdminRead(role);
        Long merchantCount = jdbc.queryForObject("SELECT count(*) FROM merchant WHERE merchant_id=?", Long.class,
                merchantId);
        if (merchantCount == null || merchantCount == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "MERCHANT_NOT_FOUND"));
        }
        return ResponseEntity.ok(jdbc.queryForList("""
                SELECT redirect_uri_id,redirect_uri,status,created_at,updated_at
                FROM merchant_redirect_uri
                WHERE merchant_id=?
                ORDER BY CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END,created_at
                """, merchantId));
    }

    /**
     * Adds redirect.
     *
     * @param role       the authenticated portal role
     * @param merchantId the merchant identifier
     * @param request    the current request or request payload
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PostMapping("/admin/merchants/{merchantId}/redirect-uris")
    public ResponseEntity<?> addRedirect(@RequestHeader(ROLE_HEADER) String role,
            @PathVariable UUID merchantId, @RequestBody RedirectRegistration request) {
        authorization.requireSuperAdmin(role);
        final String normalized;
        try {
            normalized = redirectUrlPolicy.validateAndNormalize(request == null ? null : request.redirectUrl());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REDIRECT_URL", "message", e.getMessage()));
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(
                    "INSERT INTO merchant_redirect_uri(redirect_uri_id,merchant_id,redirect_uri,status) VALUES (?,?,?,'ACTIVE')",
                    id, merchantId, normalized);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "REDIRECT_URL_ALREADY_EXISTS",
                    "message", "This return URL is already registered for the merchant.",
                    "redirectUrl", normalized));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "redirectUriId", id, "redirectUrl", normalized, "status", "ACTIVE"));
    }

    /**
     * Updates redirect.
     *
     * @param role          the authenticated portal role
     * @param merchantId    the merchant identifier
     * @param redirectUriId the redirect URI identifier identifier
     * @param request       the current request or request payload
     * @return the resulting {@code ResponseEntity<?>} value
     */
    @PutMapping("/admin/merchants/{merchantId}/redirect-uris/{redirectUriId}")
    public ResponseEntity<?> updateRedirect(@RequestHeader(ROLE_HEADER) String role,
            @PathVariable UUID merchantId, @PathVariable UUID redirectUriId,
            @RequestBody RedirectUpdate request) {
        authorization.requireSuperAdmin(role);
        final String normalized;
        try {
            normalized = redirectUrlPolicy.validateAndNormalize(request == null ? null : request.redirectUrl());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REDIRECT_URL", "message", e.getMessage()));
        }

        String status = request == null || request.status() == null || request.status().isBlank()
                ? "ACTIVE"
                : request.status().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "INVALID_REDIRECT_STATUS",
                    "message", "status must be ACTIVE or DISABLED"));
        }

        try {
            int updated = jdbc.update("""
                    UPDATE merchant_redirect_uri
                    SET redirect_uri=?,status=?,updated_at=now(),version=version+1
                    WHERE redirect_uri_id=? AND merchant_id=?
                    """, normalized, status, redirectUriId, merchantId);
            if (updated == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "REDIRECT_URL_NOT_FOUND"));
            }
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "REDIRECT_URL_ALREADY_EXISTS",
                    "message", "Another return URL registration already uses this URL for the merchant.",
                    "redirectUrl", normalized));
        }

        return ResponseEntity.ok(jdbc.queryForMap("""
                SELECT redirect_uri_id,redirect_uri,status,created_at,updated_at
                FROM merchant_redirect_uri
                WHERE redirect_uri_id=? AND merchant_id=?
                """, redirectUriId, merchantId));
    }
}
