package com.paymentgateway.provider.mpu;

import com.paymentgateway.common.MpuSignature;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.EnquirePaymentCommand;
import com.paymentgateway.contract.provider.EnquirePaymentResult;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.contract.provider.ProviderDescriptor;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Provides mpu provider RPC application services.
 */
@DubboService(version="1.0.0",group="provider-mpu",timeout=8000)
public class MpuProviderRpcService implements ProviderRpcService {
    private final MpuProperties props; private final MpuApiClient api;
    /**
     * Creates a new {@code MpuProviderRpcService} instance.
     *
     * @param props the props value
     * @param api the API value
     */
    public MpuProviderRpcService(MpuProperties props,MpuApiClient api){this.props=props;this.api=api;}
    /**
     * Executes the descriptor operation.
     *
     * @return the resulting {@code ProviderDescriptor} value
     */
    @Override public ProviderDescriptor descriptor(){return new ProviderDescriptor(ProviderCode.MPU,"MPU National Payment Gateway",java.util.Set.of(ProviderCapability.HOSTED_PAYMENT,ProviderCapability.ENQUIRY,ProviderCapability.CALLBACK,ProviderCapability.VOID));}

    /**
     * Executes the initiate operation.
     *
     * @param c the initiate payment command
     * @return the resulting {@code InitiatePaymentResult} value
     */
    @Override public InitiatePaymentResult initiate(InitiatePaymentCommand c){
        String amount=MpuAmountMapper.toTwelveDigits(c.amountMinor(),c.currency());
        var signatureValues=List.of(n(props.merchantId()),n(c.providerOrderId()),n(c.description()),amount,nvl(props.currencyCode(),"104"),n(props.categoryCode()),"","","",n(props.frontendUrl()),n(props.backendUrl()));
        String hash=MpuSignature.sign(props.secretKey(),signatureValues);
        Map<String,String> fields=new LinkedHashMap<>(); fields.put("Version",nvl(props.version(),"2.0")); fields.put("merchantID",props.merchantId()); fields.put("invoiceNo",c.providerOrderId());
        fields.put("productDesc",c.description()); fields.put("amount",amount); fields.put("currencyCode",nvl(props.currencyCode(),"104"));
        put(fields,"categoryCode",props.categoryCode()); put(fields,"FrontendURL",props.frontendUrl()); put(fields,"BackendURL",props.backendUrl()); fields.put("hashValue",hash);
        return new InitiatePaymentResult(ProviderOutcome.SUCCESS,ProviderCode.MPU,c.providerOrderId(),null,null,"FORM_POST",null,props.paymentUrl(),Map.copyOf(fields),null,"MPU hosted payment form prepared");
    }
    /**
     * Executes the enquire operation.
     *
     * @param c the enquire payment command
     * @return the resulting {@code EnquirePaymentResult} value
     */
    @Override public EnquirePaymentResult enquire(EnquirePaymentCommand c){
        String date=c.providerOrderDate(); if(date==null||date.isBlank()) date=LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE); else date=date.replace("-","");
        try{var r=api.inquiry(c.paymentId(),c.providerOrderId(),date); String supplied=r.get("hashValue");
            List<String> responseValues=List.of(n(r.get("merchantID")),n(r.get("respCode")),n(r.get("pan")),n(r.get("amount")),n(r.get("invoiceNo")),n(r.get("tranRef")),n(r.get("approvalCode")),n(r.get("dateTime")),n(r.get("status")),n(r.get("failReason")),n(r.get("userDefined1")),n(r.get("userDefined2")),n(r.get("userDefined3")));
            if(!MpuSignature.verify(props.secretKey(),responseValues,supplied)) return unknown(c.providerOrderId(),"MPU enquiry response signature verification failed");
            var outcome=MpuStatusMapper.map(r.get("respCode"),r.get("status"));
            return new EnquirePaymentResult(outcome,c.providerOrderId(),null,null,r.get("status")+(r.get("failReason")==null?"":" - "+r.get("failReason")),r.get("tranRef"),r.get("approvalCode"),null,r.get("dateTime"),"MPU",r.get("respCode"));
        }catch(ResourceAccessException|HttpStatusCodeException e){return unknown(c.providerOrderId(),"MPU enquiry unavailable");}
    }
    /**
     * Executes the unknown operation.
     *
     * @param id the resource identifier
     * @param msg the response message
     * @return the resulting {@code EnquirePaymentResult} value
     */
    private static EnquirePaymentResult unknown(String id,String msg){return new EnquirePaymentResult(ProviderOutcome.UNKNOWN,id,null,null,null,null,null,null,null,"MPU",msg);}
    /**
     * Executes the n operation.
     *
     * @param v the v value
     * @return the resulting n text
     */
    private static String n(String v){return v==null?"":v;} /**
     * Executes the nvl operation.
     *
     * @param v the v value
     * @param d the d value
     * @return the resulting nvl text
     */
    private static String nvl(String v,String d){return v==null||v.isBlank()?d:v;} /**
     * Executes the put operation.
     *
     * @param m the m value
     * @param k the k value
     * @param v the v value
     */
    private static void put(Map<String,String> m,String k,String v){if(v!=null&&!v.isBlank())m.put(k,v);}
}
