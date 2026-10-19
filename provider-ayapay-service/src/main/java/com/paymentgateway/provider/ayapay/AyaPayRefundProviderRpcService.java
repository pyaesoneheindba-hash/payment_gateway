package com.paymentgateway.provider.ayapay;

import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.ProviderRefundCommand;
import com.paymentgateway.contract.provider.ProviderRefundResult;
import com.paymentgateway.contract.provider.RefundProviderRpcService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Provides aya pay refund provider RPC application services.
 */
@DubboService(version="1.0.0",group="provider-ayapay",timeout=8000)
public class AyaPayRefundProviderRpcService implements RefundProviderRpcService {
    private final AyaPayApiClient api; /**
     * Creates a new {@code AyaPayRefundProviderRpcService} instance.
     *
     * @param api the API value
     */
    public AyaPayRefundProviderRpcService(AyaPayApiClient api){this.api=api;}
    /**
     * Executes the refund operation.
     *
     * @param c the provider refund command
     * @return the resulting {@code ProviderRefundResult} value
     */
    @Override public ProviderRefundResult refund(ProviderRefundCommand c){if(c.amountMinor()!=c.originalAmountMinor())return new ProviderRefundResult(ProviderOutcome.REJECTED,ProviderCode.AYAPAY,null,null,"PARTIAL_REFUND_UNSUPPORTED","AYA PAY guide's refund request has no amount field; this adapter only permits a full refund");if(c.providerReferenceId()==null||c.providerReferenceId().isBlank())return new ProviderRefundResult(ProviderOutcome.REJECTED,ProviderCode.AYAPAY,null,null,"REFERENCE_REQUIRED","AYA PAY referenceNumber is required");try{var r=api.refund(c.paymentId(),c.providerOrderId(),c.providerReferenceId());if(r!=null&&Integer.valueOf(200).equals(r.err()))return new ProviderRefundResult(ProviderOutcome.REFUNDED,ProviderCode.AYAPAY,c.providerReferenceId(),"REFUNDED",String.valueOf(r.err()),r.message());return new ProviderRefundResult(ProviderOutcome.REJECTED,ProviderCode.AYAPAY,null,null,r==null?null:String.valueOf(r.err()),r==null?"Empty AYA PAY refund response":r.message());}catch(ResourceAccessException e){return unknown("AYA PAY refund transport outcome unknown; reconcile before retry");}catch(HttpStatusCodeException e){return e.getStatusCode().is5xxServerError()?unknown("AYA PAY refund server outcome unknown; reconcile before retry"):new ProviderRefundResult(ProviderOutcome.REJECTED,ProviderCode.AYAPAY,null,null,String.valueOf(e.getStatusCode().value()),"AYA PAY refund rejected");}}
    /**
     * Executes the unknown operation.
     *
     * @param msg the response message
     * @return the resulting {@code ProviderRefundResult} value
     */
    private static ProviderRefundResult unknown(String msg){return new ProviderRefundResult(ProviderOutcome.UNKNOWN,ProviderCode.AYAPAY,null,null,null,msg);}
}
