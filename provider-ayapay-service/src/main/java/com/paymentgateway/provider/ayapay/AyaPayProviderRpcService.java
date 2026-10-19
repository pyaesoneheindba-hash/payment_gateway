package com.paymentgateway.provider.ayapay;

import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.EnquirePaymentCommand;
import com.paymentgateway.contract.provider.EnquirePaymentResult;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.contract.provider.ProviderDescriptor;
import com.paymentgateway.provider.ayapay.api.AyaQrPaymentRequest;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Provides aya pay provider RPC application services.
 */
@DubboService(version="1.0.0",group="provider-ayapay",timeout=8000)
public class AyaPayProviderRpcService implements ProviderRpcService {
    private final AyaPayProperties props; private final AyaPayApiClient api;
    /**
     * Creates a new {@code AyaPayProviderRpcService} instance.
     *
     * @param props the props value
     * @param api the API value
     */
    public AyaPayProviderRpcService(AyaPayProperties props,AyaPayApiClient api){this.props=props;this.api=api;}

    /**
     * Executes the descriptor operation.
     *
     * @return the resulting {@code ProviderDescriptor} value
     */
    @Override public ProviderDescriptor descriptor(){return new ProviderDescriptor(ProviderCode.AYAPAY,"AYA PAY Online Merchant",java.util.Set.of(ProviderCapability.QR_PAYMENT,ProviderCapability.MMQR_PAYMENT,ProviderCapability.PUSH_PAYMENT,ProviderCapability.CUSTOMER_PRESENT_QR,ProviderCapability.ENQUIRY,ProviderCapability.CALLBACK,ProviderCapability.REFUND));}

    /**
     * Executes the initiate operation.
     *
     * @param c the initiate payment command
     * @return the resulting {@code InitiatePaymentResult} value
     */
    @Override public InitiatePaymentResult initiate(InitiatePaymentCommand c){
        Map<String,String> o=c.providerOptions()==null?Collections.emptyMap():c.providerOptions(); String mode=o.getOrDefault("paymentMethod","QR").trim().toUpperCase(Locale.ROOT);
        try{
            if("PUSH".equals(mode)) return push(c,o);
            if(!"QR".equals(mode)) return rejected(c,"Unsupported AYAPAY paymentMethod: "+mode);
            String service=nonBlank(o.get("serviceCode"),props.qrServiceCode()); require(service,"AYA_QR_SERVICE_CODE/serviceCode");
            int ttl=intOpt(o.get("timelimit"),props.qrTimeLimit()==null?5:props.qrTimeLimit()); boolean mmqr=boolOpt(o.get("mmqr"),Boolean.TRUE.equals(props.mmqr()));
            var req=new AyaQrPaymentRequest(c.amountMinor(),c.currency(),c.providerOrderId(),o.getOrDefault("externalAdditionalData",""),service,ttl,mmqr);
            var r=api.requestQr(c.paymentId(),req);
            if(r!=null&&Integer.valueOf(200).equals(r.err())&&r.data()!=null){String qr=mmqr&&notBlank(r.data().mmqrdata())?r.data().mmqrdata():r.data().qrdata();
                return new InitiatePaymentResult(ProviderOutcome.SUCCESS,ProviderCode.AYAPAY,c.providerOrderId(),r.data().referenceNumber(),null,"DISPLAY_QR",qr,null,null,String.valueOf(r.err()),r.message());}
            return rejected(c,r==null?"Empty AYA PAY response":r.message());
        }catch(ResourceAccessException e){return unknown(c,"AYA PAY transport outcome unknown; reconcile before retry");}
         catch(HttpStatusCodeException e){return new InitiatePaymentResult(e.getStatusCode().is5xxServerError()?ProviderOutcome.UNKNOWN:ProviderOutcome.REJECTED,ProviderCode.AYAPAY,c.providerOrderId(),null,null,"NONE",null,null,null,String.valueOf(e.getStatusCode().value()),"AYA PAY HTTP error");}
    }

    /**
     * Executes the push operation.
     *
     * @param c the initiate payment command
     * @param o the o value
     * @return the resulting {@code InitiatePaymentResult} value
     */
    private InitiatePaymentResult push(InitiatePaymentCommand c,Map<String,String> o){
        String phone=o.get("customerPhone"); require(phone,"providerOptions.customerPhone"); String service=nonBlank(o.get("serviceCode"),props.pushServiceCode()); require(service,"AYA_PUSH_SERVICE_CODE/serviceCode");
        int ttl=intOpt(o.get("timelimit"),props.pushTimeLimit()==null?15:props.pushTimeLimit()); var r=api.requestPush(c.paymentId(),c.providerOrderId(),c.amountMinor(),c.currency(),o.getOrDefault("externalAdditionalData",""),phone,service,ttl);
        if(r!=null&&Integer.valueOf(200).equals(r.err())&&r.data()!=null){String ref=r.data().referenceNumber();String deeplink="ayapay://mLink/screen=DynamicConfirm?requestedId="+ref;
            return new InitiatePaymentResult(ProviderOutcome.SUCCESS,ProviderCode.AYAPAY,c.providerOrderId(),ref,null,"DEEPLINK",null,deeplink,null,String.valueOf(r.err()),r.message());}
        return rejected(c,r==null?"Empty AYA PAY response":r.message());
    }

    /**
     * Executes the enquire operation.
     *
     * @param c the enquire payment command
     * @return the resulting {@code EnquirePaymentResult} value
     */
    @Override public EnquirePaymentResult enquire(EnquirePaymentCommand c){
        if(c.providerReferenceId()==null||c.providerReferenceId().isBlank()) return unknownEnquiry(c,"AYA PAY referenceNumber is missing; cannot call status API safely");
        try{String mode="DEEPLINK".equalsIgnoreCase(c.providerNextAction())?"PUSH":"QR"; var r=api.checkStatus(c.paymentId(),c.providerOrderId(),c.providerReferenceId(),mode); if(r==null)return unknownEnquiry(c,"Empty AYA PAY status response");
            ProviderOutcome outcome=AyaPayStatusMapper.map(r.status());
            return new EnquirePaymentResult(outcome,c.providerOrderId(),c.providerReferenceId(),null,r.status(),r.transRefId(),null,null,null,"AYA PAY",r.message());
        }catch(ResourceAccessException|HttpStatusCodeException e){return unknownEnquiry(c,"AYA PAY status check unavailable");}
    }

    /**
     * Executes the rejected operation.
     *
     * @param c the initiate payment command
     * @param msg the response message
     * @return the resulting {@code InitiatePaymentResult} value
     */
    private static InitiatePaymentResult rejected(InitiatePaymentCommand c,String msg){return new InitiatePaymentResult(ProviderOutcome.REJECTED,ProviderCode.AYAPAY,c.providerOrderId(),null,null,"NONE",null,null,null,null,msg);}
    /**
     * Executes the unknown operation.
     *
     * @param c the initiate payment command
     * @param msg the response message
     * @return the resulting {@code InitiatePaymentResult} value
     */
    private static InitiatePaymentResult unknown(InitiatePaymentCommand c,String msg){return new InitiatePaymentResult(ProviderOutcome.UNKNOWN,ProviderCode.AYAPAY,c.providerOrderId(),null,null,"NONE",null,null,null,null,msg);}
    /**
     * Executes the unknown enquiry operation.
     *
     * @param c the enquire payment command
     * @param msg the response message
     * @return the resulting {@code EnquirePaymentResult} value
     */
    private static EnquirePaymentResult unknownEnquiry(EnquirePaymentCommand c,String msg){return new EnquirePaymentResult(ProviderOutcome.UNKNOWN,c.providerOrderId(),c.providerReferenceId(),null,null,null,null,null,null,"AYA PAY",msg);}
    /**
     * Executes the int opt operation.
     *
     * @param v the v value
     * @param d the d value
     * @return the resulting int opt value
     */
    private static int intOpt(String v,int d){if(v==null||v.isBlank())return d;return Integer.parseInt(v);}
    /**
     * Executes the bool opt operation.
     *
     * @param v the v value
     * @param d whether d is enabled
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    private static boolean boolOpt(String v,boolean d){return v==null||v.isBlank()?d:Boolean.parseBoolean(v);}
    /**
     * Executes the non blank operation.
     *
     * @param a the a value
     * @param b the b value
     * @return the resulting non blank text
     */
    private static String nonBlank(String a,String b){return a!=null&&!a.isBlank()?a:b;}
    /**
     * Executes the not blank operation.
     *
     * @param s the s value
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    private static boolean notBlank(String s){return s!=null&&!s.isBlank();}
    /**
     * Executes the require operation.
     *
     * @param v the v value
     * @param name the name value
     */
    private static void require(String v,String name){if(v==null||v.isBlank())throw new IllegalArgumentException(name+" is required");}
}
