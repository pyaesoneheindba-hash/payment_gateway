package com.paymentgateway.callback;

import java.util.List;
import java.util.TreeMap;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paymentgateway.audit.util.AuditJson;
import com.paymentgateway.common.Hashing;
import com.paymentgateway.common.MpuSignature;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.contract.payment.PaymentRpcService;
import com.paymentgateway.contract.payment.ProviderCallbackCommand;

/**
 * Exposes HTTP endpoints for mpu callback operations.
 */
@RestController
@RequestMapping("/callbacks/mpu")
public class MpuCallbackController {
	private final MpuCallbackProperties props;
	@DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
	private PaymentRpcService payments;

	/**
	 * Creates a new {@code MpuCallbackController} instance.
	 *
	 * @param props the props value
	 */
	public MpuCallbackController(MpuCallbackProperties props) {
		this.props = props;
	}

	/**
	 * Executes the frontend operation.
	 *
	 * @param form the form value
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	@PostMapping(path = "/frontend", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<?> frontend(@RequestParam MultiValueMap<String, String> form) {
		return handle(form, "FRONTEND");
	}

	/**
	 * Executes the backend operation.
	 *
	 * @param form the form value
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	@PostMapping(path = "/backend", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<?> backend(@RequestParam MultiValueMap<String, String> form) {
		return handle(form, "BACKEND");
	}

	/**
	 * Executes the handle operation.
	 *
	 * @param form the form value
	 * @param type the type value
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	private ResponseEntity<?> handle(MultiValueMap<String, String> form, String type) {
		var q = form.toSingleValueMap();
		String invoice = q.get("invoiceNo");
		if (invoice == null || invoice.isBlank())
			return ResponseEntity.badRequest().body("invoiceNo required");
		List<String> signed = List.of(n(q.get("merchantID")), n(q.get("respCode")), n(q.get("pan")), n(q.get("amount")),
				n(q.get("invoiceNo")), n(q.get("tranRef")), n(q.get("approvalCode")), n(q.get("dateTime")),
				n(q.get("status")), n(q.get("failReason")), n(q.get("userDefined1")), n(q.get("userDefined2")),
				n(q.get("userDefined3")), n(q.get("categoryCode")));
		boolean verified = MpuSignature.verify(props.secretKey(), signed, q.get("hashValue"));
		if (!verified)
			return ResponseEntity.status(401).body("invalid MPU hashValue");
		String raw = AuditJson.write(new TreeMap<>(q));
		String dedupe = Hashing.sha256(raw + "|" + type);
		var r = payments.handleProviderCallback(new ProviderCallbackCommand(ProviderCode.MPU, type, invoice, null,
				q.get("amount"), q.get("status"), q.get("respCode"), q.get("tranRef"), q.get("approvalCode"), null,
				null, q.get("dateTime"), "MPU", q.get("pan"), null, q.get("failReason"), true, dedupe, raw));
		return ResponseEntity.ok(r);
	}

	/**
	 * Executes the n operation.
	 *
	 * @param s the s value
	 * @return the resulting n text
	 */
	private static String n(String s) {
		return s == null ? "" : s;
	}
}
