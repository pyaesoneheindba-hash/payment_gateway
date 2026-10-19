package com.paymentgateway.provider.abank;

import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.EnquirePaymentCommand;
import com.paymentgateway.contract.provider.EnquirePaymentResult;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.contract.provider.ProviderDescriptor;
import com.paymentgateway.provider.abank.api.AbankCreateOrderRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Provides abank provider RPC application services.
 */
@DubboService(version="1.0.0", group="provider-abank", timeout=8000)
public class AbankProviderRpcService implements ProviderRpcService {
    private static final DateTimeFormatter ABANK_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AbankApiClient api; private final AbankProperties properties;
    /**
     * Creates a new {@code AbankProviderRpcService} instance.
     *
     * @param api the API value
     * @param properties the properties value
     */
    public AbankProviderRpcService(AbankApiClient api,AbankProperties properties){this.api=api;this.properties=properties;}

    /**
     * Executes the descriptor operation.
     *
     * @return the resulting {@code ProviderDescriptor} value
     */
    @Override
    public ProviderDescriptor descriptor(){return new ProviderDescriptor(ProviderCode.ABANK,"ABank / APlus MMQR",java.util.Set.of(ProviderCapability.QR_PAYMENT,ProviderCapability.MMQR_PAYMENT,ProviderCapability.ENQUIRY,ProviderCapability.CALLBACK));}

    /**
     * Executes the initiate operation.
     *
     * @param c the initiate payment command
     * @return the resulting {@code InitiatePaymentResult} value
     */
    @Override
    public InitiatePaymentResult initiate(InitiatePaymentCommand c){
        String requestNo=UUID.randomUUID().toString();
        String providerOrderId=c.providerOrderId();
        var req=new AbankCreateOrderRequest(requestNo,providerOrderId,properties.merchantId(),AbankAmountMapper.fromMinor(c.amountMinor(),c.currency()),c.currency(),0,c.description(),LocalDateTime.now().format(ABANK_TIME));
        try{
            var response=api.create(c.paymentId(), req);
            if(response!=null && Integer.valueOf(200).equals(response.respondCode()) && response.data()!=null && response.data().qr()!=null){
                return new InitiatePaymentResult(ProviderOutcome.SUCCESS,ProviderCode.ABANK,providerOrderId,null,requestNo,"DISPLAY_QR",response.data().qr(),null,null,String.valueOf(response.respondCode()),response.respondMessage());
            }
            return new InitiatePaymentResult(ProviderOutcome.REJECTED,ProviderCode.ABANK,providerOrderId,null,requestNo,"NONE",null,null,null,response==null?null:String.valueOf(response.respondCode()),response==null?"Empty provider response":response.respondMessage());
        }catch(ResourceAccessException timeoutOrIo){
            return new InitiatePaymentResult(ProviderOutcome.UNKNOWN,ProviderCode.ABANK,providerOrderId,null,requestNo,"NONE",null,null,null,null,"Provider transport outcome unknown; query by orderId before retrying");
        }catch(HttpStatusCodeException http){
            ProviderOutcome outcome=http.getStatusCode().is5xxServerError()?ProviderOutcome.UNKNOWN:ProviderOutcome.REJECTED;
            return new InitiatePaymentResult(outcome,ProviderCode.ABANK,providerOrderId,null,requestNo,"NONE",null,null,null,String.valueOf(http.getStatusCode().value()),"Provider HTTP error");
        }
    }

    /**
     * Executes the enquire operation.
     *
     * @param c the enquire payment command
     * @return the resulting {@code EnquirePaymentResult} value
     */
    @Override
    public EnquirePaymentResult enquire(EnquirePaymentCommand c){
        try{
            var r=api.enquire(c.paymentId(), c.providerOrderId());
            if(r==null || r.data()==null){return unknown(c.providerOrderId(),"Empty provider enquiry response");}
            var d=r.data();
            return new EnquirePaymentResult(AbankStatusMapper.map(d.paymentTxnStatus()),d.orderId(),null,d.paymentTxnStatus(),d.txnStatusDescription(),d.paymentTxnID(),d.billNo(),d.endToEndId(),d.transactionDateTime(),d.institutionName(),r.respondMessage());
        }catch(ResourceAccessException|HttpStatusCodeException e){
            return unknown(c.providerOrderId(),"Provider enquiry unavailable");
        }
    }

    /**
     * Executes the unknown operation.
     *
     * @param orderId the provider or merchant order identifier
     * @param message the message value
     * @return the resulting {@code EnquirePaymentResult} value
     */
    private static EnquirePaymentResult unknown(String orderId,String message){
        return new EnquirePaymentResult(ProviderOutcome.UNKNOWN,orderId,null,null,null,null,null,null,null,null,message);
    }
}
