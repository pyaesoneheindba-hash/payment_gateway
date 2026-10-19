package com.paymentgateway.callback;

import com.paymentgateway.audit.util.AuditJson;
import com.paymentgateway.common.Hashing;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.contract.payment.PaymentRpcService;
import com.paymentgateway.contract.payment.ProviderCallbackCommand;
import java.util.TreeMap;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP endpoints for abank callback operations.
 */
@RestController
@RequestMapping("/callbacks/abank")
public class AbankCallbackController {
	@DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
	private PaymentRpcService payments;

	/**
	 * Executes the success operation.
	 *
	 * @param q the q value
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	@GetMapping("/success")
	public ResponseEntity<?> success(@RequestParam MultiValueMap<String, String> q) {
		return handle(q, "SUCCESS");
	}

	/**
	 * Executes the fail operation.
	 *
	 * @param q the q value
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	@GetMapping("/fail")
	public ResponseEntity<?> fail(@RequestParam MultiValueMap<String, String> q) {
		return handle(q, "FAIL");
	}

	/**
	 * Executes the handle operation.
	 *
	 * @param q    the q value
	 * @param type the type value
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	private ResponseEntity<?> handle(MultiValueMap<String, String> q, String type) {
		String orderId = q.getFirst("orderId");
		if (orderId == null || orderId.isBlank())
			return ResponseEntity.badRequest().body("orderId required");
		String raw = AuditJson.write(new TreeMap<>(q.toSingleValueMap()));
		String dedupe = Hashing.sha256(raw + "|" + type);
		var r = payments.handleProviderCallback(
				new ProviderCallbackCommand(ProviderCode.ABANK, type, orderId, null, q.getFirst("amount"),
						q.getFirst("status"), null, q.getFirst("transactionId"), null, q.getFirst("billNo"),
						q.getFirst("endToEndId"), q.getFirst("transactionDateTime"), q.getFirst("institutionName"),
						null, q.getFirst("errorCode"), q.getFirst("errorDesc"), null, dedupe, raw));
		return ResponseEntity.ok(r);
	}
}
