package com.paymentgateway.core.service;

import com.paymentgateway.common.PaymentStatus;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.common.ProviderOrderIds;
import com.paymentgateway.common.RefundStatus;
import com.paymentgateway.contract.payment.CreatePaymentCommand;
import com.paymentgateway.contract.error.IdempotencyConflictException;
import com.paymentgateway.contract.error.InvalidPaymentStateException;
import com.paymentgateway.contract.error.PaymentNotFoundException;
import com.paymentgateway.contract.payment.DuplicateMerchantOrderException;
import com.paymentgateway.contract.payment.RefundPaymentCommand;
import com.paymentgateway.contract.provider.EnquirePaymentResult;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderRefundResult;
import com.paymentgateway.contract.provider.ProviderVoidResult;
import com.paymentgateway.core.persistence.entity.IdempotencyRecordId;
import com.paymentgateway.core.persistence.entity.OutboxEventEntity;
import com.paymentgateway.core.persistence.entity.PaymentEntity;
import com.paymentgateway.core.persistence.entity.PaymentRefundEntity;
import com.paymentgateway.core.persistence.entity.ProviderCallbackEntity;
import com.paymentgateway.core.persistence.entity.ProviderTransactionEntity;
import com.paymentgateway.core.persistence.repo.IdempotencyRecordRepository;
import com.paymentgateway.core.persistence.repo.OutboxEventRepository;
import com.paymentgateway.core.persistence.repo.PaymentRefundRepository;
import com.paymentgateway.core.persistence.repo.PaymentRepository;
import com.paymentgateway.core.persistence.repo.ProviderCallbackRepository;
import com.paymentgateway.core.persistence.repo.ProviderTransactionRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides payment persistence application services.
 */
@Service
public class PaymentPersistenceService {
    public static final String CREATE_PAYMENT = "CREATE_PAYMENT";
    public static final String PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";
    public static final String PAYMENT_CREATED = "PAYMENT_CREATED";
    private static final EnumSet<RefundStatus> REFUND_RESERVING_STATUSES = EnumSet.of(
            RefundStatus.REQUESTED, RefundStatus.PENDING, RefundStatus.UNKNOWN, RefundStatus.SUCCEEDED);

    private final PaymentRepository payments;
    private final ProviderTransactionRepository providerTransactions;
    private final IdempotencyRecordRepository idempotency;
    private final ProviderCallbackRepository callbacks;
    private final OutboxEventRepository outbox;
    private final PaymentRefundRepository refunds;
    private final JdbcTemplate jdbc;

    /**
     * Creates a new {@code PaymentPersistenceService} instance.
     *
     * @param payments the payments value
     * @param providerTransactions the provider transactions value
     * @param idempotency the idempotency value
     * @param callbacks the callbacks value
     * @param outbox the outbox value
     * @param refunds the refunds value
     * @param jdbc the jdbc value
     */
    public PaymentPersistenceService(
            PaymentRepository payments,
            ProviderTransactionRepository providerTransactions,
            IdempotencyRecordRepository idempotency,
            ProviderCallbackRepository callbacks,
            OutboxEventRepository outbox,
            PaymentRefundRepository refunds,
            JdbcTemplate jdbc) {
        this.payments = payments;
        this.providerTransactions = providerTransactions;
        this.idempotency = idempotency;
        this.callbacks = callbacks;
        this.outbox = outbox;
        this.refunds = refunds;
        this.jdbc = jdbc;
    }

    /**
     * Immutable data carrier for reservation.
     */
    public record Reservation(UUID paymentId, boolean created) {}
    /**
     * Immutable data carrier for refund reservation.
     */
    public record RefundReservation(UUID refundId, boolean created) {}

    /**
     * Reserves and create.
     *
     * @param c the create payment command
     * @return the resulting {@code Reservation} value
     */
    @Transactional
    public Reservation reserveAndCreate(CreatePaymentCommand c) {
        var key = new IdempotencyRecordId(c.merchantId(), CREATE_PAYMENT, c.idempotencyKey());
        UUID id = UUID.randomUUID();
        int inserted = idempotency.insertIfAbsent(c.merchantId(), CREATE_PAYMENT, c.idempotencyKey(), c.requestHash(), id, Instant.now());
        if (inserted == 0) {
            var winner = idempotency.findById(key).orElseThrow();
            if (!winner.getRequestHash().equals(c.requestHash())) throw new IdempotencyConflictException();
            if (winner.getResourceId() == null) throw new IllegalStateException("Existing idempotency reservation has no payment resource id");
            return new Reservation(winner.getResourceId(), false);
        }
        // merchantOrderId is a merchant-scoped business identifier and must identify exactly one payment.
        // Same-request retries are handled above by Idempotency-Key. A different Idempotency-Key with an
        // already-used merchantOrderId is therefore a conflict, not a server error.
        if (payments.findByMerchantIdAndMerchantOrderId(c.merchantId(), c.merchantOrderId()).isPresent()) {
            throw new DuplicateMerchantOrderException(c.merchantOrderId());
        }

        String provider = ProviderCode.normalize(c.providerCode());
        var payment = PaymentEntity.initiated(id, c.merchantId(), c.merchantOrderId(), c.amountMinor(), c.currency());
        payment.markProviderRequested(provider, ProviderOrderIds.forProvider(provider, id, c.merchantOrderId()));
        try {
            // Flush here so the database unique constraint is translated inside this service method. The
            // pre-check above gives a clear fast path, while the constraint remains the race-safe authority.
            payments.saveAndFlush(payment);
            // A newly-created payment changes dashboard totals even before the provider produces a later
            // status transition. Emit a portal-only lifecycle event in the same DB transaction so live
            // dashboards refresh immediately after commit. The notification consumer deliberately does
            // not fan PAYMENT_CREATED out to merchant webhook endpoints.
            emitPaymentCreated(payment);
        }
        catch (DataIntegrityViolationException e) {
            if (isMerchantOrderConflict(e)) throw new DuplicateMerchantOrderException(c.merchantOrderId(), e);
            throw e;
        }
        return new Reservation(id, true);
    }

    /**
     * Determines whether merchant order conflict.
     *
     * @param error the failure that triggered error handling
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    private static boolean isMerchantOrderConflict(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("uq_payment_merchant_order")
                    || (message.contains("merchant_id") && message.contains("merchant_order_id")
                        && message.toLowerCase().contains("duplicate")))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reserves refundable value under a pessimistic lock on the payment aggregate.
     * REQUESTED/PENDING/UNKNOWN refunds continue to reserve value until they fail,
     * preventing concurrent provider calls from collectively over-refunding a payment.
     */
    @Transactional
    public RefundReservation reserveRefund(RefundPaymentCommand c) {
        PaymentEntity payment = requireOwnedForUpdate(c.paymentId(), c.merchantId());
        var existing = refunds.findByMerchantIdAndMerchantRefundId(c.merchantId(), c.merchantRefundId());
        if (existing.isPresent()) {
            var r = existing.get();
            if (!r.getPaymentId().equals(c.paymentId()) || r.getAmountMinor() != c.amountMinor()) throw new IdempotencyConflictException();
            return new RefundReservation(r.getRefundId(), false);
        }
        if (c.amountMinor() <= 0 || c.amountMinor() > payment.getAmountMinor()) {
            throw new IllegalArgumentException("Refund amount must be > 0 and <= original payment amount");
        }
        if (payment.getStatus() != PaymentStatus.SUCCEEDED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new InvalidPaymentStateException("Only succeeded or partially-refunded payments can be refunded");
        }

        long reserved = refunds.sumReservedAmountByPaymentId(payment.getPaymentId(), REFUND_RESERVING_STATUSES);
        if (Math.addExact(reserved, c.amountMinor()) > payment.getAmountMinor()) {
            throw new IllegalArgumentException("Refund would exceed original payment amount including in-flight refunds");
        }

        String operation = "REFUND:" + payment.getPaymentId();
        UUID refundId = UUID.randomUUID();
        var key = new IdempotencyRecordId(c.merchantId(), operation, c.idempotencyKey());
        int inserted = idempotency.insertIfAbsent(c.merchantId(), operation, c.idempotencyKey(), c.requestHash(), refundId, Instant.now());
        if (inserted == 0) {
            var winner = idempotency.findById(key).orElseThrow();
            if (!winner.getRequestHash().equals(c.requestHash())) throw new IdempotencyConflictException();
            return new RefundReservation(winner.getResourceId(), false);
        }
        refunds.saveAndFlush(PaymentRefundEntity.requested(refundId, payment, c.merchantRefundId(), c.amountMinor()));
        return new RefundReservation(refundId, true);
    }

    /**
     * Reserves payment operation.
     *
     * @param merchantId the merchant identifier
     * @param paymentId the payment identifier
     * @param operation the operation value
     * @param idempotencyKey the idempotency key used to deduplicate retries
     * @param requestHash the canonical request hash used for idempotency checks
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    @Transactional
    public boolean reservePaymentOperation(UUID merchantId, UUID paymentId, String operation, String idempotencyKey, String requestHash) {
        requireOwned(paymentId, merchantId);
        var op = operation + ":" + paymentId;
        var key = new IdempotencyRecordId(merchantId, op, idempotencyKey);
        int inserted = idempotency.insertIfAbsent(merchantId, op, idempotencyKey, requestHash, paymentId, Instant.now());
        if (inserted == 0) {
            var winner = idempotency.findById(key).orElseThrow();
            if (!winner.getRequestHash().equals(requestHash)) throw new IdempotencyConflictException();
            return false;
        }
        return true;
    }

    /**
     * Completes payment operation.
     *
     * @param merchantId the merchant identifier
     * @param paymentId the payment identifier
     * @param operation the operation value
     * @param idempotencyKey the idempotency key used to deduplicate retries
     */
    @Transactional
    public void completePaymentOperation(UUID merchantId, UUID paymentId, String operation, String idempotencyKey) {
        idempotency.findById(new IdempotencyRecordId(merchantId, operation + ":" + paymentId, idempotencyKey)).ifPresent(x -> x.complete());
    }

    /**
     * Executes the require operation.
     *
     * @param id the resource identifier
     * @return the resulting {@code PaymentEntity} value
     */
    @Transactional(readOnly = true)
    public PaymentEntity require(UUID id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    /**
     * Retrieves and validates owned.
     *
     * @param id the resource identifier
     * @param merchantId the merchant identifier
     * @return the resulting {@code PaymentEntity} value
     */
    @Transactional(readOnly = true)
    public PaymentEntity requireOwned(UUID id, UUID merchantId) {
        var p = require(id);
        if (!p.getMerchantId().equals(merchantId)) throw new PaymentNotFoundException(id);
        return p;
    }

    /**
     * Retrieves and validates for update.
     *
     * @param id the resource identifier
     * @return the resulting {@code PaymentEntity} value
     */
    private PaymentEntity requireForUpdate(UUID id) {
        return payments.findByIdForUpdate(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    /**
     * Retrieves and validates owned for update.
     *
     * @param id the resource identifier
     * @param merchantId the merchant identifier
     * @return the resulting {@code PaymentEntity} value
     */
    private PaymentEntity requireOwnedForUpdate(UUID id, UUID merchantId) {
        var p = requireForUpdate(id);
        if (!p.getMerchantId().equals(merchantId)) throw new PaymentNotFoundException(id);
        return p;
    }

    /**
     * Retrieves and validates refund.
     *
     * @param id the resource identifier
     * @return the resulting {@code PaymentRefundEntity} value
     */
    @Transactional(readOnly = true)
    public PaymentRefundEntity requireRefund(UUID id) {
        return refunds.findById(id).orElseThrow(() -> new IllegalArgumentException("Refund not found: " + id));
    }

    /**
     * Configures checkout.
     *
     * @param merchantId the merchant identifier
     * @param paymentId the payment identifier
     * @param presentationMode the presentation mode value
     * @param expiresAt the expires at timestamp
     * @return the resulting {@code PaymentEntity} value
     */
    @Transactional
    public PaymentEntity configureCheckout(UUID merchantId, UUID paymentId, String presentationMode, Instant expiresAt) {
        PaymentEntity payment = requireOwnedForUpdate(paymentId, merchantId);
        payment.configureCheckout(presentationMode, expiresAt);
        return payment;
    }

    /**
     * Expires payment.
     *
     * @param merchantId the merchant identifier
     * @param paymentId the payment identifier
     * @return the resulting {@code PaymentEntity} value
     */
    @Transactional
    public PaymentEntity expirePayment(UUID merchantId, UUID paymentId) {
        PaymentEntity payment = requireOwnedForUpdate(paymentId, merchantId);
        PaymentStatus before = payment.getStatus();
        if (payment.expireIfDue(Instant.now())) {
            emitPaymentStatusChanged(payment, before, "CHECKOUT_EXPIRY");
            completeCreateIdempotencyWhenTerminal(paymentId, payment.getStatus());
        }
        return payment;
    }

    /**
     * Persists initiate outcome.
     *
     * @param paymentId the payment identifier
     * @param r the refund or provider result
     */
    @Transactional
    public void persistInitiateOutcome(UUID paymentId, InitiatePaymentResult r) {
        var p = requireForUpdate(paymentId);
        PaymentStatus before = p.getStatus();
        saveProviderTransaction(paymentId, r.providerCode(), "INITIATE", r.providerRequestNo(), r.providerOrderId(),
                r.providerReferenceId(), r.outcome().name(), null, null, r.responseCode(), r.responseMessage());
        switch (r.outcome()) {
            case SUCCESS -> {
                if (r.qr() != null && !r.qr().isBlank()) {
                    p.applyInitiation(r.providerOrderId(), r.providerReferenceId(), nvl(r.nextAction(), "DISPLAY_QR"),
                            r.qr(), r.redirectUrl(), r.formFields(), PaymentStatus.QR_GENERATED);
                }
                else if ((r.redirectUrl() != null && !r.redirectUrl().isBlank()) || (r.formFields() != null && !r.formFields().isEmpty())) {
                    p.applyInitiation(r.providerOrderId(), r.providerReferenceId(), nvl(r.nextAction(), "REDIRECT"),
                            null, r.redirectUrl(), r.formFields(), PaymentStatus.ACTION_REQUIRED);
                }
                else {
                    p.applyInitiation(r.providerOrderId(), r.providerReferenceId(), nvl(r.nextAction(), "NONE"),
                            null, null, null, PaymentStatus.PENDING);
                }
            }
            case PENDING -> p.applyInitiation(r.providerOrderId(), r.providerReferenceId(), nvl(r.nextAction(), "NONE"),
                    null, r.redirectUrl(), r.formFields(), PaymentStatus.PENDING);
            case REJECTED, NOT_FOUND -> p.markFailed(r.providerOrderId());
            case REFUNDED -> p.applyVerifiedStatus(PaymentStatus.REFUNDED);
            case VOIDED -> p.applyVerifiedStatus(PaymentStatus.VOIDED);
            default -> p.markUnknown(r.providerOrderId());
        }
        emitPaymentStatusChanged(p, before, "PROVIDER_INITIATION");
        completeCreateIdempotencyWhenTerminal(paymentId, p.getStatus());
    }

    /**
     * Persists refund outcome.
     *
     * @param refundId the refund identifier
     * @param result the operation result
     * @return the resulting {@code PaymentRefundEntity} value
     */
    @Transactional
    public PaymentRefundEntity persistRefundOutcome(UUID refundId, ProviderRefundResult result) {
        var refund = requireRefund(refundId);
        var payment = requireForUpdate(refund.getPaymentId());
        PaymentStatus before = payment.getStatus();
        refund.apply(result);
        saveProviderTransaction(payment.getPaymentId(), result.providerCode(), "REFUND", refund.getMerchantRefundId(),
                payment.getProviderOrderId(), result.providerRefundReferenceId(), result.outcome().name(),
                result.providerStatus(), null, result.responseCode(), result.responseMessage());
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            long total = refunds.sumAmountByPaymentIdAndStatus(payment.getPaymentId(), RefundStatus.SUCCEEDED);
            payment.applyVerifiedStatus(total >= payment.getAmountMinor() ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        }
        emitPaymentStatusChanged(payment, before, "REFUND_RESULT");
        if (refund.getStatus() == RefundStatus.SUCCEEDED || refund.getStatus() == RefundStatus.FAILED) {
            idempotency.findByResourceId(refundId).ifPresent(x -> x.complete());
        }
        return refund;
    }

    /**
     * Persists void outcome.
     *
     * @param paymentId the payment identifier
     * @param result the operation result
     * @return the resulting {@code PaymentEntity} value
     */
    @Transactional
    public PaymentEntity persistVoidOutcome(UUID paymentId, ProviderVoidResult result) {
        var p = requireForUpdate(paymentId);
        PaymentStatus before = p.getStatus();
        saveProviderTransaction(paymentId, result.providerCode(), "VOID", null, p.getProviderOrderId(),
                result.providerReferenceId(), result.outcome().name(), result.providerStatus(), null,
                result.responseCode(), result.responseMessage());
        if (result.outcome() == com.paymentgateway.common.ProviderOutcome.VOIDED
                || result.outcome() == com.paymentgateway.common.ProviderOutcome.SUCCESS) {
            p.applyVerifiedStatus(PaymentStatus.VOIDED);
        }
        /*
         * UNKNOWN/REJECTED are outcomes of the void operation, not proof that the
         * original successful payment became UNKNOWN/FAILED. Preserve payment truth
         * and let explicit enquiry/recovery resolve an uncertain void.
         */
        emitPaymentStatusChanged(p, before, "VOID_RESULT");
        return p;
    }

    /**
     * Executes the record callback operation.
     *
     * @param c the com.paymentgateway.contract.payment.provider callback command
     * @param payloadHash the payload hash value
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    @Transactional
    public boolean recordCallback(com.paymentgateway.contract.payment.ProviderCallbackCommand c, String payloadHash) {
        if (callbacks.existsByProviderCodeAndDedupeKey(c.providerCode(), c.dedupeKey())) return false;
        try {
            callbacks.saveAndFlush(ProviderCallbackEntity.received(c, payloadHash));
            return true;
        }
        catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /**
     * Executes the mark callback processed operation.
     *
     * @param provider the provider value
     * @param dedupe the dedupe value
     * @param verified the verified value
     * @param msg the response message
     */
    @Transactional
    public void markCallbackProcessed(String provider, String dedupe, String verified, String msg) {
        callbacks.findByProviderCodeAndDedupeKey(provider, dedupe).ifPresent(e -> e.processed(verified, msg));
    }

    /**
     * Executes the mark callback failed operation.
     *
     * @param provider the provider value
     * @param dedupe the dedupe value
     * @param msg the response message
     */
    @Transactional
    public void markCallbackFailed(String provider, String dedupe, String msg) {
        callbacks.findByProviderCodeAndDedupeKey(provider, dedupe).ifPresent(e -> e.failed(msg));
    }

    /**
     * Finds by provider order.
     *
     * @param provider the provider value
     * @param order the order value
     * @return the resulting {@code PaymentEntity} value
     */
    @Transactional(readOnly = true)
    public PaymentEntity findByProviderOrder(String provider, String order) {
        return payments.findBySelectedProviderCodeAndProviderOrderId(provider, order)
                .orElseThrow(() -> new PaymentNotFoundException(order));
    }

    /**
     * Executes the attach provider reference if absent operation.
     *
     * @param paymentId the payment identifier
     * @param ref the ref value
     * @return the resulting {@code PaymentEntity} value
     */
    @Transactional
    public PaymentEntity attachProviderReferenceIfAbsent(UUID paymentId, String ref) {
        var p = requireForUpdate(paymentId);
        p.attachProviderReferenceIfAbsent(ref);
        return p;
    }

    /**
     * Applies verified enquiry.
     *
     * @param payment the payment value
     * @param result the operation result
     * @return the resulting {@code PaymentEntity} value
     */
    @Transactional
    public PaymentEntity applyVerifiedEnquiry(PaymentEntity payment, EnquirePaymentResult result) {
        var managed = requireForUpdate(payment.getPaymentId());
        managed.attachProviderReferenceIfAbsent(result.providerReferenceId());
        PaymentStatus before = managed.getStatus();
        PaymentStatus target = PaymentStateMapper.fromProvider(result.outcome());
        if (managed.getStatus() != PaymentStatus.REFUNDED && managed.getStatus() != PaymentStatus.VOIDED) {
            // A provider payment enquiry normally still reports SUCCESS after a partial
            // refund. Do not erase the locally authoritative refund aggregate state.
            if (!(managed.getStatus() == PaymentStatus.PARTIALLY_REFUNDED && target == PaymentStatus.SUCCEEDED)) {
                managed.applyVerifiedStatus(target);
                emitPaymentStatusChanged(managed, before, "PROVIDER_ENQUIRY_VERIFIED");
            }
        }
        completeCreateIdempotencyWhenTerminal(managed.getPaymentId(), managed.getStatus());
        return managed;
    }

    /**
     * Executes the emit payment created operation.
     *
     * @param p the payment entity
     */
    private void emitPaymentCreated(PaymentEntity p) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", p.getPaymentId().toString());
        payload.put("merchantId", p.getMerchantId().toString());
        payload.put("merchantOrderId", p.getMerchantOrderId());
        payload.put("amountMinor", p.getAmountMinor());
        payload.put("currency", p.getCurrency());
        payload.put("status", p.getStatus().name());
        payload.put("providerCode", p.getSelectedProviderCode());
        payload.put("source", "PAYMENT_CREATED");
        outbox.save(OutboxEventEntity.pending(p.getPaymentId(), PAYMENT_CREATED, payload));
    }

    /**
     * Executes the emit payment status changed operation.
     *
     * @param p the payment entity
     * @param before the before value
     * @param source the source value
     */
    private void emitPaymentStatusChanged(PaymentEntity p, PaymentStatus before, String source) {
        if (before == p.getStatus()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", p.getPaymentId().toString());
        payload.put("merchantId", p.getMerchantId().toString());
        payload.put("merchantOrderId", p.getMerchantOrderId());
        payload.put("amountMinor", p.getAmountMinor());
        payload.put("currency", p.getCurrency());
        payload.put("previousStatus", before.name());
        payload.put("status", p.getStatus().name());
        payload.put("providerCode", p.getSelectedProviderCode());
        if (p.getProviderOrderId() != null) payload.put("providerOrderId", p.getProviderOrderId());
        if (p.getProviderReferenceId() != null) payload.put("providerReferenceId", p.getProviderReferenceId());
        payload.put("source", source);
        outbox.save(OutboxEventEntity.pending(p.getPaymentId(), PAYMENT_STATUS_CHANGED, payload));
    }

    /**
     * Persists provider transaction.
     *
     * @param paymentId the payment identifier
     * @param provider the provider value
     * @param operation the operation value
     * @param requestNo the request no value
     * @param orderId the provider or merchant order identifier
     * @param ref the ref value
     * @param outcome the outcome value
     * @param status the payment or delivery status
     * @param txId the tx identifier value
     * @param code the provider or status code
     * @param msg the response message
     */
    private void saveProviderTransaction(UUID paymentId, String provider, String operation, String requestNo,
            String orderId, String ref, String outcome, String status, String txId, String code, String msg) {
        Integer no = jdbc.queryForObject("""
            INSERT INTO payment_provider_attempt_counter(payment_id,last_attempt_no)
            VALUES (?,1)
            ON CONFLICT(payment_id) DO UPDATE
            SET last_attempt_no=payment_provider_attempt_counter.last_attempt_no+1,
                updated_at=now()
            RETURNING last_attempt_no
            """, Integer.class, paymentId);
        if (no == null) throw new IllegalStateException("Could not allocate provider transaction attempt number");
        providerTransactions.save(ProviderTransactionEntity.of(paymentId, no, provider, operation, requestNo,
                orderId, ref, outcome, status, txId, code, msg));
    }

    /**
     * Completes create idempotency when terminal.
     *
     * @param paymentId the payment identifier
     * @param status the payment or delivery status
     */
    private void completeCreateIdempotencyWhenTerminal(UUID paymentId, PaymentStatus status) {
        if (status != PaymentStatus.UNKNOWN && status != PaymentStatus.PENDING && status != PaymentStatus.ACTION_REQUIRED
                && status != PaymentStatus.PROVIDER_REQUESTED && status != PaymentStatus.QR_GENERATED
                && status != PaymentStatus.QR_REQUESTED) {
            idempotency.findByOperationAndResourceId(CREATE_PAYMENT, paymentId).ifPresent(x -> x.complete());
        }
    }

    /**
     * Executes the nvl operation.
     *
     * @param v the v value
     * @param d the d value
     * @return the resulting nvl text
     */
    private static String nvl(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}
