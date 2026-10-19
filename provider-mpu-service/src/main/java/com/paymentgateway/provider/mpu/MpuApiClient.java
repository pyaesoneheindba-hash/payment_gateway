package com.paymentgateway.provider.mpu;

import com.paymentgateway.audit.service.ProviderExchangeAuditService;
import com.paymentgateway.audit.util.AuditJson;
import com.paymentgateway.common.MpuSignature;
import com.paymentgateway.provider.mpu.http.MpuHttpService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Provides client-side access to mpu API.
 */
@Component
public class MpuApiClient {
    private final MpuHttpService http; private final MpuProperties props; private final ProviderExchangeAuditService audit;
    /**
     * Creates a new {@code MpuApiClient} instance.
     *
     * @param http the HTTP value
     * @param props the props value
     * @param audit the audit value
     */
    public MpuApiClient(MpuHttpService http,MpuProperties props,ProviderExchangeAuditService audit){this.http=http;this.props=props;this.audit=audit;}
    /**
     * Executes the inquiry operation.
     *
     * @param paymentId the payment identifier
     * @param invoiceNo the invoice no value
     * @param inquiryDate the inquiry date value
     * @return the resulting {@code MpuResponse} value
     */
    public MpuResponse inquiry(UUID paymentId,String invoiceNo,String inquiryDate){String hash=MpuSignature.sign(props.secretKey(),List.of(props.merchantId(),invoiceNo,inquiryDate,"I"));String request=AuditJson.write(Map.of("merchantID",props.merchantId(),"invoiceNo",invoiceNo,"inquiryDate",inquiryDate,"actionType","I","hashValue","***REDACTED***"));UUID id=audit.start(paymentId,"MPU","INQUIRY",null,invoiceNo,"POST",props.actionUrl(),AuditJson.write(Map.of("Content-Type","application/x-www-form-urlencoded")),request);try{var response=http.inquiry(props.merchantId(),invoiceNo,inquiryDate,"I",hash);String body=response.getBody();var parsed=MpuResponseParser.parse(body);audit.complete(id,response.getStatusCode().value(),AuditJson.write(response.getHeaders()),body,parsed.get("respCode"),parsed.get("failReason"),"RECEIVED");return parsed;}catch(HttpStatusCodeException e){audit.fail(id,e.getStatusCode().value(),AuditJson.write(e.getResponseHeaders()),e.getResponseBodyAsString(),e,e.getStatusCode().is5xxServerError()?"UNKNOWN":"REJECTED");throw e;}catch(ResourceAccessException e){audit.fail(id,null,null,null,e,"UNKNOWN");throw e;}}
    /**
     * Voids payment.
     *
     * @param paymentId the payment identifier
     * @param invoiceNo the invoice no value
     * @param amount the amount value
     * @param currencyCode the currency code value
     * @return the resulting {@code MpuResponse} value
     */
    public MpuResponse voidPayment(UUID paymentId,String invoiceNo,String amount,String currencyCode){String hash=MpuSignature.sign(props.secretKey(),List.of(props.merchantId(),invoiceNo,"V",amount,currencyCode));String request=AuditJson.write(Map.of("merchantID",props.merchantId(),"invoiceNo",invoiceNo,"actionType","V","amount",amount,"currencyCode",currencyCode,"hashValue","***REDACTED***"));UUID id=audit.start(paymentId,"MPU","VOID",null,invoiceNo,"POST",props.actionUrl(),AuditJson.write(Map.of("Content-Type","application/x-www-form-urlencoded")),request);try{var response=http.voidPayment(props.merchantId(),invoiceNo,"V",amount,currencyCode,hash);String body=response.getBody();var parsed=MpuResponseParser.parse(body);audit.complete(id,response.getStatusCode().value(),AuditJson.write(response.getHeaders()),body,parsed.get("respCode"),parsed.get("status"),"RECEIVED",parsed.get("tranRef"));return parsed;}catch(HttpStatusCodeException e){audit.fail(id,e.getStatusCode().value(),AuditJson.write(e.getResponseHeaders()),e.getResponseBodyAsString(),e,e.getStatusCode().is5xxServerError()?"UNKNOWN":"REJECTED");throw e;}catch(ResourceAccessException e){audit.fail(id,null,null,null,e,"UNKNOWN");throw e;}}
}
