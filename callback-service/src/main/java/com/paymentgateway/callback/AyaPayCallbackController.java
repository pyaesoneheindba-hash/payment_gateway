package com.paymentgateway.callback;

import com.paymentgateway.audit.util.AuditJson;
import com.paymentgateway.common.AyaPayCrypto;
import com.paymentgateway.common.Hashing;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.contract.payment.PaymentRpcService;
import com.paymentgateway.contract.payment.ProviderCallbackCommand;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP endpoints for aya pay callback operations.
 */
@RestController
@RequestMapping("/callbacks/ayapay")
public class AyaPayCallbackController {
	/**
	 * Immutable data carrier for envelope.
	 */
	public record Envelope(String paymentResult, String checksum, String externalTransactionId, Object refundResult,
			Boolean payByOtherPay, String debitorName, String mmqrRefId, String walletName) {
	}

	/**
	 * Immutable data carrier for decrypted.
	 */
	public record Decrypted(String status, String createdAt, String transRefId, String externalTransactionId,
			String referenceNumber, Long totalAmount, Long amount, String currency) {
	}

	private final AyaPayCallbackProperties props;
	@DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
	private PaymentRpcService payments;

	/**
	 * Creates a new {@code AyaPayCallbackController} instance.
	 *
	 * @param props the props value
	 */
	public AyaPayCallbackController(AyaPayCallbackProperties props) {
		this.props = props;
	}

	/**
	 * Executes the callback operation.
	 *
	 * @param body the request or response body
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	@PostMapping
	public ResponseEntity<?> callback(@RequestBody Envelope body) {
		if (body == null || body.paymentResult() == null || body.paymentResult().isBlank())
			return ResponseEntity.badRequest().body("paymentResult required");
		String decrypted = AyaPayCrypto.decryptPaymentResult(body.paymentResult(), props.decryptionKey());
		Decrypted d = AuditJson.read(decrypted, Decrypted.class);
		String orderId = nonBlank(d == null ? null : d.externalTransactionId(), body.externalTransactionId());
		if (orderId == null || orderId.isBlank())
			return ResponseEntity.badRequest().body("externalTransactionId required");
		String reference = d == null ? null : d.referenceNumber();
		String status = d == null ? null : d.status();
		String tx = d == null ? null : d.transRefId();
		String amount = d == null ? null : String.valueOf(d.amount() != null ? d.amount() : d.totalAmount());
		// The supplied guide documents checksum in the payload but does not define its
		// algorithm, so it is not treated as authentication.
		String raw = AuditJson.write(body);
		String dedupe = Hashing.sha256(raw + "|AYAPAY");
		var result = payments.handleProviderCallback(new ProviderCallbackCommand(ProviderCode.AYAPAY, "PAYMENT",
				orderId, reference, amount, status, null, tx, null, null, null, d == null ? null : d.createdAt(),
				"AYA PAY", null, null, null, null, dedupe, raw));
		return ResponseEntity.ok(result);
	}

	/**
	 * Executes the non blank operation.
	 *
	 * @param a the a value
	 * @param b the b value
	 * @return the resulting non blank text
	 */
	private static String nonBlank(String a, String b) {
		return a != null && !a.isBlank() ? a : b;
	}
}
