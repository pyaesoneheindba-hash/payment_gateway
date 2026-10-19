package com.paymentgateway.api.web;

import com.paymentgateway.contract.payment.PaymentRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP endpoints for provider operations.
 */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {
	@DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
	private PaymentRpcService paymentRpcService;

	/**
	 * Executes the list operation.
	 *
	 * @return the resulting {@code Object} value
	 */
	@GetMapping
	public Object list() {
		return paymentRpcService.listProviders();
	}
}
