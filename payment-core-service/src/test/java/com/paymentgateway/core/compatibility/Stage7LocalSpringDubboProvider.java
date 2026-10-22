package com.paymentgateway.core.compatibility;

import org.springframework.context.annotation.Profile;

import java.util.Map;
import java.util.Set;

import org.apache.dubbo.config.annotation.DubboService;

import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.EnquirePaymentCommand;
import com.paymentgateway.contract.provider.EnquirePaymentResult;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderDescriptor;
import com.paymentgateway.contract.provider.ProviderRpcService;

/** Test-only Spring-scanned Dubbo service; never uses an external bank client. */
@Profile("stage7-boot4-compatibility")
@DubboService(group = "stage7-boot4-compatibility", version = "1.0.0",
              protocol = "tri", retries = 0, timeout = 4000)
public class Stage7LocalSpringDubboProvider implements ProviderRpcService {
    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                "STAGE7-LOCAL",
                "Local Boot/Dubbo Compatibility Fixture (Not a Bank)",
                Set.of(ProviderCapability.QR_PAYMENT, ProviderCapability.ENQUIRY));
    }

    @Override
    public InitiatePaymentResult initiate(InitiatePaymentCommand command) {
        return new InitiatePaymentResult(
                ProviderOutcome.PENDING, "STAGE7-LOCAL",
                command.providerOrderId(), "LOCAL-REF-" + command.paymentId(),
                "LOCAL-REQUEST", "DISPLAY_QR", "MOCK-QR-BOOT4", null,
                Map.of("source", "compatibility-smoke"),
                "PENDING", "No external provider called");
    }

    @Override
    public EnquirePaymentResult enquire(EnquirePaymentCommand command) {
        return new EnquirePaymentResult(
                ProviderOutcome.PENDING, command.providerOrderId(),
                "LOCAL-REF-" + command.paymentId(),
                100, "LOCAL-PENDING",
                null, null, null, null,
                "Local Boot/Dubbo Compatibility Fixture",
                "No external provider called");
    }
}
