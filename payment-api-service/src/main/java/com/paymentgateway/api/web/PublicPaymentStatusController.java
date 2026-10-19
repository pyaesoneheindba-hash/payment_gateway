package com.paymentgateway.api.web;

import com.paymentgateway.common.PaymentStatus;
import com.paymentgateway.contract.payment.PaymentRpcService;
import com.paymentgateway.contract.payment.PaymentView;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Exposes HTTP endpoints for public payment status operations.
 */
@RestController
@RequestMapping("/api/v1/public/payments")
public class PublicPaymentStatusController {
	private static final String AUTH_MODE_HEADER = "X-Payment-Gateway-Auth-Mode";
	private static final String PUBLIC_AUTH = "PUBLIC_TOKEN";
	private static final String TOKEN_PAYMENT_ID = "X-Payment-Gateway-Token-Payment-Id";
	private static final String TOKEN_MERCHANT_ORDER_ID = "X-Payment-Gateway-Token-Merchant-Order-Id";

	@DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
	private PaymentRpcService paymentRpcService;

	private final long pollIntervalMs;
	private final long sseTimeoutMs;
	private final ScheduledExecutorService scheduler;

	/**
	 * Creates a new {@code PublicPaymentStatusController} instance.
	 *
	 * @param pollIntervalMs the poll interval ms value
	 * @param sseTimeoutMs   the SSE timeout ms value
	 */
	public PublicPaymentStatusController(@Value("${payment.public-status.poll-interval-ms:3000}") long pollIntervalMs,
			@Value("${payment.public-status.sse-timeout-ms:900000}") long sseTimeoutMs) {
		this.pollIntervalMs = Math.max(1000, pollIntervalMs);
		this.sseTimeoutMs = Math.max(30000, sseTimeoutMs);
		ThreadFactory tf = r -> {
			Thread t = new Thread(r, "payment-status-sse");
			t.setDaemon(true);
			return t;
		};
		this.scheduler = Executors.newScheduledThreadPool(4, tf);
	}

	/**
	 * Executes the status operation.
	 *
	 * @param merchantId           the merchant identifier
	 * @param authMode             the trusted authentication mode set by the
	 *                             gateway
	 * @param tokenPaymentId       the token payment identifier value
	 * @param tokenMerchantOrderId the token merchant order identifier value
	 * @param paymentId            the payment identifier
	 * @return the resulting {@code PaymentStatusResponse} value
	 */
	@GetMapping("/{paymentId}/status")
	public PaymentStatusResponse status(@RequestHeader("X-Merchant-Id") UUID merchantId,
			@RequestHeader(AUTH_MODE_HEADER) String authMode,
			@RequestHeader(value = TOKEN_PAYMENT_ID, required = false) String tokenPaymentId,
			@RequestHeader(value = TOKEN_MERCHANT_ORDER_ID, required = false) String tokenMerchantOrderId,
			@PathVariable UUID paymentId) {
		requirePublic(authMode);
		return response(loadBound(merchantId, paymentId, tokenPaymentId, tokenMerchantOrderId));
	}

	/**
	 * Streams live portal payment events and heartbeat messages to the
	 * authenticated client.
	 *
	 * @param merchantId           the merchant identifier
	 * @param authMode             the trusted authentication mode set by the
	 *                             gateway
	 * @param tokenPaymentId       the token payment identifier value
	 * @param tokenMerchantOrderId the token merchant order identifier value
	 * @param paymentId            the payment identifier
	 * @return the resulting {@code SseEmitter} value
	 */
	@GetMapping(path = "/{paymentId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events(@RequestHeader("X-Merchant-Id") UUID merchantId,
			@RequestHeader(AUTH_MODE_HEADER) String authMode,
			@RequestHeader(value = TOKEN_PAYMENT_ID, required = false) String tokenPaymentId,
			@RequestHeader(value = TOKEN_MERCHANT_ORDER_ID, required = false) String tokenMerchantOrderId,
			@PathVariable UUID paymentId) {
		requirePublic(authMode);
		SseEmitter emitter = new SseEmitter(sseTimeoutMs);
		AtomicReference<PaymentStatus> lastStatus = new AtomicReference<>();
		AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();

		Runnable poll = () -> {
			try {
				PaymentView view = loadBound(merchantId, paymentId, tokenPaymentId, tokenMerchantOrderId);
				PaymentStatus previous = lastStatus.getAndSet(view.status());
				if (previous == null || previous != view.status()) {
					emitter.send(SseEmitter.event().id(UUID.randomUUID().toString()).name("payment-status")
							.data(response(view), MediaType.APPLICATION_JSON));
				}
				if (isTerminal(view.status())) {
					emitter.complete();
					cancel(taskRef.get());
				}
			} catch (IOException e) {
				emitter.completeWithError(e);
				cancel(taskRef.get());
			} catch (RuntimeException e) {
				emitter.completeWithError(e);
				cancel(taskRef.get());
			}
		};

		ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(poll, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
		taskRef.set(task);
		emitter.onCompletion(() -> cancel(taskRef.get()));
		emitter.onTimeout(() -> cancel(taskRef.get()));
		emitter.onError(error -> cancel(taskRef.get()));
		return emitter;
	}

	/**
	 * Loads bound.
	 *
	 * @param merchantId           the merchant identifier
	 * @param paymentId            the payment identifier
	 * @param tokenPaymentId       the token payment identifier value
	 * @param tokenMerchantOrderId the token merchant order identifier value
	 * @return the resulting {@code PaymentView} value
	 */
	private PaymentView loadBound(UUID merchantId, UUID paymentId, String tokenPaymentId, String tokenMerchantOrderId) {
		PaymentView view = paymentRpcService.getPayment(merchantId, paymentId);
		if (tokenPaymentId != null && !tokenPaymentId.isBlank()
				&& !view.paymentId().toString().equalsIgnoreCase(tokenPaymentId)) {
			throw new IllegalArgumentException("Public status token is not bound to this payment");
		}
		if (tokenMerchantOrderId != null && !tokenMerchantOrderId.isBlank()
				&& !view.merchantOrderId().equals(tokenMerchantOrderId)) {
			throw new IllegalArgumentException("Public status token is not bound to this merchantOrderId");
		}
		if ((tokenPaymentId == null || tokenPaymentId.isBlank())
				&& (tokenMerchantOrderId == null || tokenMerchantOrderId.isBlank())) {
			throw new IllegalArgumentException("Public status token has no payment binding");
		}
		return view;
	}

	/**
	 * Executes the response operation.
	 *
	 * @param view the view value
	 * @return the resulting {@code PaymentStatusResponse} value
	 */
	private static PaymentStatusResponse response(PaymentView view) {
		return new PaymentStatusResponse(view.paymentId(), view.merchantOrderId(), view.status(), view.providerCode(),
				view.providerOrderId(), view.providerReferenceId());
	}

	/**
	 * Determines whether terminal.
	 *
	 * @param status the payment or delivery status
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean isTerminal(PaymentStatus status) {
		return status == PaymentStatus.SUCCEEDED || status == PaymentStatus.PARTIALLY_REFUNDED
				|| status == PaymentStatus.REFUNDED || status == PaymentStatus.VOIDED || status == PaymentStatus.FAILED
				|| status == PaymentStatus.EXPIRED;
	}

	/**
	 * Retrieves and validates public.
	 *
	 * @param authMode the trusted authentication mode set by the gateway
	 */
	private static void requirePublic(String authMode) {
		if (!PUBLIC_AUTH.equals(authMode))
			throw new IllegalArgumentException("Public payment status requires a public bearer token");
	}

	/**
	 * Executes the cancel operation.
	 *
	 * @param task the task value
	 */
	private static void cancel(ScheduledFuture<?> task) {
		if (task != null)
			task.cancel(false);
	}

	/**
	 * Executes the shutdown operation.
	 */
	@PreDestroy
	public void shutdown() {
		scheduler.shutdownNow();
	}
}
