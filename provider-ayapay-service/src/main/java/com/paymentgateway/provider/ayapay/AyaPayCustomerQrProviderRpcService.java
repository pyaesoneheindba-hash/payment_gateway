package com.paymentgateway.provider.ayapay;

import java.util.Collections;
import java.util.Map;

import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import com.paymentgateway.audit.util.AuditJson;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.CustomerPresentQrProviderRpcService;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderCustomerQrInspectCommand;
import com.paymentgateway.contract.provider.ProviderCustomerQrInspectResult;
import com.paymentgateway.contract.provider.ProviderCustomerQrPaymentCommand;

/**
 * Provides aya pay customer QR provider RPC application services.
 */
@DubboService(version = "1.0.0", group = "provider-ayapay", timeout = 8000)
public class AyaPayCustomerQrProviderRpcService implements CustomerPresentQrProviderRpcService {
	private final AyaPayApiClient api;
	private final AyaPayProperties props;

	/**
	 * Creates a new {@code AyaPayCustomerQrProviderRpcService} instance.
	 *
	 * @param api   the API value
	 * @param props the props value
	 */
	public AyaPayCustomerQrProviderRpcService(AyaPayApiClient api, AyaPayProperties props) {
		this.api = api;
		this.props = props;
	}

	/**
	 * Executes the inspect customer QR operation.
	 *
	 * @param c the provider customer QR inspect command
	 * @return the resulting {@code ProviderCustomerQrInspectResult} value
	 */
	@Override
	public ProviderCustomerQrInspectResult inspectCustomerQr(ProviderCustomerQrInspectCommand c) {
		try {
			var r = api.inspectCustomerQr(c.qrData(), c.deviceId(), c.shopId());
			if (r == null)
				return rejected("Empty AYA PAY customer QR response");
			String phone = findPhone(r.data());
			String ref = findString(r.data(), "referenceNumber", "reference", "id");
			return new ProviderCustomerQrInspectResult(
					Integer.valueOf(200).equals(r.err()) ? ProviderOutcome.SUCCESS : ProviderOutcome.REJECTED,
					ProviderCode.AYAPAY, phone, ref, AuditJson.write(r.data()),
					r.err() == null ? null : String.valueOf(r.err()), r.message());
		} catch (ResourceAccessException | HttpStatusCodeException e) {
			return new ProviderCustomerQrInspectResult(ProviderOutcome.UNKNOWN, ProviderCode.AYAPAY, null, null, null,
					null, "AYA PAY customer QR lookup unavailable");
		}
	}

	/**
	 * Executes the pay customer QR operation.
	 *
	 * @param c the provider customer QR payment command
	 * @return the resulting {@code InitiatePaymentResult} value
	 */
	@Override
	public InitiatePaymentResult payCustomerQr(ProviderCustomerQrPaymentCommand c) {
		Map<String, String> o = c.providerOptions() == null ? Collections.emptyMap() : c.providerOptions();
		String service = nonBlank(o.get("serviceCode"), props.pushServiceCode());
		require(service, "AYA_PUSH_SERVICE_CODE/serviceCode");
		require(c.customerPhone(), "customerPhone");
		int ttl = intOpt(o.get("timelimit"), props.pushTimeLimit() == null ? 15 : props.pushTimeLimit());
		try {
			var r = api.requestPush(c.paymentId(), c.providerOrderId(), c.amountMinor(), c.currency(),
					o.getOrDefault("externalAdditionalData", ""), c.customerPhone(), service, ttl);
			if (r != null && Integer.valueOf(200).equals(r.err()) && r.data() != null) {
				String ref = r.data().referenceNumber();
				return new InitiatePaymentResult(ProviderOutcome.SUCCESS, ProviderCode.AYAPAY, c.providerOrderId(), ref,
						null, "DEEPLINK", null, "ayapay://mLink/screen=DynamicConfirm?requestedId=" + ref, null,
						String.valueOf(r.err()), r.message());
			}
			return new InitiatePaymentResult(ProviderOutcome.REJECTED, ProviderCode.AYAPAY, c.providerOrderId(), null,
					null, "NONE", null, null, null, r == null ? null : String.valueOf(r.err()),
					r == null ? "Empty AYA PAY push response" : r.message());
		} catch (ResourceAccessException e) {
			return unknown(c, "AYA PAY push outcome unknown; reconcile before retry");
		} catch (HttpStatusCodeException e) {
			return e.getStatusCode().is5xxServerError()
					? unknown(c, "AYA PAY push server outcome unknown; reconcile before retry")
					: new InitiatePaymentResult(ProviderOutcome.REJECTED, ProviderCode.AYAPAY, c.providerOrderId(),
							null, null, "NONE", null, null, null, String.valueOf(e.getStatusCode().value()),
							"AYA PAY push rejected");
		}
	}

	/**
	 * Executes the rejected operation.
	 *
	 * @param msg the response message
	 * @return the resulting {@code ProviderCustomerQrInspectResult} value
	 */
	private static ProviderCustomerQrInspectResult rejected(String msg) {
		return new ProviderCustomerQrInspectResult(ProviderOutcome.REJECTED, ProviderCode.AYAPAY, null, null, null,
				null, msg);
	}

	/**
	 * Executes the unknown operation.
	 *
	 * @param c   the provider customer QR payment command
	 * @param msg the response message
	 * @return the resulting {@code InitiatePaymentResult} value
	 */
	private static InitiatePaymentResult unknown(ProviderCustomerQrPaymentCommand c, String msg) {
		return new InitiatePaymentResult(ProviderOutcome.UNKNOWN, ProviderCode.AYAPAY, c.providerOrderId(), null, null,
				"NONE", null, null, null, null, msg);
	}

	/**
	 * Executes the int opt operation.
	 *
	 * @param v the v value
	 * @param d the d value
	 * @return the resulting int opt value
	 */
	private static int intOpt(String v, int d) {
		return v == null || v.isBlank() ? d : Integer.parseInt(v);
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

	/**
	 * Executes the require operation.
	 *
	 * @param v the v value
	 * @param n the n value
	 */
	private static void require(String v, String n) {
		if (v == null || v.isBlank())
			throw new IllegalArgumentException(n + " is required");
	}

	/**
	 * Finds phone.
	 *
	 * @param value the value to process
	 * @return the resulting find phone text
	 */
	private static String findPhone(Object value) {
		String x = findString(value, "customerPhone", "phone", "subscriberPhone");
		return x;
	}

	/**
	 * Finds string.
	 *
	 * @param value the value to process
	 * @param keys  the keys value
	 * @return the resulting find string text
	 */
	@SuppressWarnings("unchecked")
	private static String findString(Object value, String... keys) {
		if (value instanceof Map<?, ?> m) {
			for (String key : keys) {
				Object v = m.get(key);
				if (v != null && !String.valueOf(v).isBlank())
					return String.valueOf(v);
			}
			for (Object v : m.values()) {
				String found = findString(v, keys);
				if (found != null)
					return found;
			}
		} else if (value instanceof Iterable<?> it) {
			for (Object v : it) {
				String found = findString(v, keys);
				if (found != null)
					return found;
			}
		}
		return null;
	}
}
