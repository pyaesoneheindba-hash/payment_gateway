package com.paymentgateway.provider.mpu;

import java.util.List;

import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import com.paymentgateway.common.MpuSignature;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.ProviderVoidCommand;
import com.paymentgateway.contract.provider.ProviderVoidResult;
import com.paymentgateway.contract.provider.VoidProviderRpcService;

/**
 * Provides mpu void provider RPC application services.
 */
@DubboService(version = "1.0.0", group = "provider-mpu", timeout = 8000)
public class MpuVoidProviderRpcService implements VoidProviderRpcService {
	private final MpuProperties props;
	private final MpuApiClient api;

	/**
	 * Creates a new {@code MpuVoidProviderRpcService} instance.
	 *
	 * @param props the props value
	 * @param api   the API value
	 */
	public MpuVoidProviderRpcService(MpuProperties props, MpuApiClient api) {
		this.props = props;
		this.api = api;
	}

	/**
	 * Voids payment.
	 *
	 * @param c the provider void command
	 * @return the resulting {@code ProviderVoidResult} value
	 */
	@Override
	public ProviderVoidResult voidPayment(ProviderVoidCommand c) {
		String amount = MpuAmountMapper.toTwelveDigits(c.amountMinor(), c.currency());
		String currency = props.currencyCode() == null || props.currencyCode().isBlank() ? "104" : props.currencyCode();
		try {
			var r = api.voidPayment(c.paymentId(), c.providerOrderId(), amount, currency);
			String supplied = r.get("hashValue");
			List<String> values = List.of(n(r.get("merchantID")), n(r.get("respCode")), n(r.get("pan")),
					n(r.get("amount")), n(r.get("invoiceNo")), n(r.get("tranRef")), n(r.get("approvalCode")),
					n(r.get("dateTime")), n(r.get("status")));
			if (!MpuSignature.verify(props.secretKey(), values, supplied))
				return unknown(c, "MPU void response signature verification failed");
			ProviderOutcome outcome = "00".equals(r.get("respCode")) ? ProviderOutcome.VOIDED
					: ("04".equals(r.get("respCode")) ? ProviderOutcome.NOT_FOUND : ProviderOutcome.REJECTED);
			return new ProviderVoidResult(outcome, ProviderCode.MPU, r.get("tranRef"), r.get("status"),
					r.get("respCode"), r.get("failReason"));
		} catch (ResourceAccessException | HttpStatusCodeException e) {
			return unknown(c, "MPU void outcome unknown; reconcile before retry");
		}
	}

	/**
	 * Executes the unknown operation.
	 *
	 * @param c   the provider void command
	 * @param msg the response message
	 * @return the resulting {@code ProviderVoidResult} value
	 */
	private static ProviderVoidResult unknown(ProviderVoidCommand c, String msg) {
		return new ProviderVoidResult(ProviderOutcome.UNKNOWN, ProviderCode.MPU, c.providerReferenceId(), null, null,
				msg);
	}

	/**
	 * Executes the n operation.
	 *
	 * @param v the v value
	 * @return the resulting n text
	 */
	private static String n(String v) {
		return v == null ? "" : v;
	}
}
