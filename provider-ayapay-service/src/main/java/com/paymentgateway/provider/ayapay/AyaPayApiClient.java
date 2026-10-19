package com.paymentgateway.provider.ayapay;

import com.paymentgateway.audit.service.ProviderExchangeAuditService;
import com.paymentgateway.audit.util.AuditJson;
import com.paymentgateway.provider.ayapay.api.AyaGenericResponse;
import com.paymentgateway.provider.ayapay.api.AyaPaymentResponse;
import com.paymentgateway.provider.ayapay.api.AyaPushResponse;
import com.paymentgateway.provider.ayapay.api.AyaQrPaymentRequest;
import com.paymentgateway.provider.ayapay.api.AyaRefundRequest;
import com.paymentgateway.provider.ayapay.api.AyaStatusRequest;
import com.paymentgateway.provider.ayapay.api.AyaStatusResponse;
import com.paymentgateway.provider.ayapay.http.AyaPayHttpService;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Provides client-side access to aya pay API.
 */
@Component
public class AyaPayApiClient {
    private final AyaPayHttpService http;private final AyaPayProperties props;private final AyaPayAuthService auth;private final ProviderExchangeAuditService audit;
    /**
     * Creates a new {@code AyaPayApiClient} instance.
     *
     * @param http the HTTP value
     * @param props the props value
     * @param auth the auth value
     * @param audit the audit value
     */
    public AyaPayApiClient(AyaPayHttpService http,AyaPayProperties props,AyaPayAuthService auth,ProviderExchangeAuditService audit){this.http=http;this.props=props;this.auth=auth;this.audit=audit;}
    /**
     * Executes the request QR operation.
     *
     * @param paymentId the payment identifier
     * @param request the current request or request payload
     * @return the resulting {@code AyaPaymentResponse} value
     */
    public AyaPaymentResponse requestQr(UUID paymentId,AyaQrPaymentRequest request){String path="/merchant/1.0.0/thirdparty/merchant/v2/requestQRPayment";UUID id=start(paymentId,"REQUEST_QR",request.externalTransactionId(),path,AuditJson.write(request));try{var h=auth.headers(paymentId);var resp=withOneAuthRetry(()->http.requestQr(h.sessionToken(),h.accessToken(),request),()->{var h2=auth.headers(paymentId);return http.requestQr(h2.sessionToken(),h2.accessToken(),request);});String body=resp.getBody();var parsed=AuditJson.read(body,AyaPaymentResponse.class);String ref=parsed!=null&&parsed.data()!=null?parsed.data().referenceNumber():null;complete(id,resp,body,parsed==null?null:String.valueOf(parsed.err()),parsed==null?null:parsed.message(),ref);return parsed;}catch(RuntimeException e){fail(id,e);throw e;}}
    /**
     * Executes the request push operation.
     *
     * @param paymentId the payment identifier
     * @param externalTransactionId the external transaction identifier value
     * @param amount the amount value
     * @param currency the ISO currency code
     * @param additional the additional value
     * @param customerPhone the customer phone value
     * @param serviceCode the service code value
     * @param timeLimit the time limit value
     * @return the resulting {@code AyaPushResponse} value
     */
    public AyaPushResponse requestPush(UUID paymentId,String externalTransactionId,long amount,String currency,String additional,String customerPhone,String serviceCode,int timeLimit){String path="/merchant/1.0.0/thirdparty/merchant/v2/requestPushPayment";String req=AuditJson.write(Map.of("amount",amount,"currency",currency,"externalTransactionId",externalTransactionId,"externalAdditionalData",additional==null?"":additional,"customerPhone",mask(customerPhone),"serviceCode",serviceCode,"timelimit",timeLimit));UUID id=start(paymentId,"REQUEST_PUSH",externalTransactionId,path,req);try{var h=auth.headers(paymentId);var resp=withOneAuthRetry(()->http.requestPush(h.sessionToken(),h.accessToken(),amount,currency,externalTransactionId,additional==null?"":additional,customerPhone,serviceCode,timeLimit),()->{var h2=auth.headers(paymentId);return http.requestPush(h2.sessionToken(),h2.accessToken(),amount,currency,externalTransactionId,additional==null?"":additional,customerPhone,serviceCode,timeLimit);});String body=resp.getBody();var parsed=AuditJson.read(body,AyaPushResponse.class);String ref=parsed!=null&&parsed.data()!=null?parsed.data().referenceNumber():null;complete(id,resp,body,parsed==null?null:String.valueOf(parsed.err()),parsed==null?null:parsed.message(),ref);return parsed;}catch(RuntimeException e){fail(id,e);throw e;}}
    /**
     * Checks status.
     *
     * @param paymentId the payment identifier
     * @param externalTransactionId the external transaction identifier value
     * @param referenceNumber the reference number value
     * @param mode the mode value
     * @return the resulting {@code AyaStatusResponse} value
     */
    public AyaStatusResponse checkStatus(UUID paymentId,String externalTransactionId,String referenceNumber,String mode){boolean push="PUSH".equalsIgnoreCase(mode);String path=push?"/merchant/1.0.0/thirdparty/merchant/checkRequestPayment":"/merchant/1.0.0/thirdparty/merchant/checkQRPayment";var req=new AyaStatusRequest(referenceNumber,externalTransactionId);UUID id=start(paymentId,"CHECK_STATUS_"+(push?"PUSH":"QR"),externalTransactionId,path,AuditJson.write(req));try{var h=auth.headers(paymentId);var resp=withOneAuthRetry(()->push?http.checkPush(h.sessionToken(),h.accessToken(),req):http.checkQr(h.sessionToken(),h.accessToken(),req),()->{var h2=auth.headers(paymentId);return push?http.checkPush(h2.sessionToken(),h2.accessToken(),req):http.checkQr(h2.sessionToken(),h2.accessToken(),req);});String body=resp.getBody();var parsed=AuditJson.read(body,AyaStatusResponse.class);complete(id,resp,body,parsed==null?null:String.valueOf(parsed.err()),parsed==null?null:parsed.message(),referenceNumber);return parsed;}catch(RuntimeException e){fail(id,e);throw e;}}
    /**
     * Executes the inspect customer QR operation.
     *
     * @param qrData the QR data value
     * @param deviceId the device identifier value
     * @param shopId the shop identifier value
     * @return the resulting {@code AyaGenericResponse} value
     */
    public AyaGenericResponse inspectCustomerQr(String qrData,String deviceId,String shopId){String path="/merchant/1.0.0/apiMerchant/qrcode/checkQrByMerchant";Map<String,String> req=Map.of("qrdata",qrData,"deviceId",deviceId,"shopId",shopId);UUID id=start(null,"CHECK_CUSTOMER_QR",null,path,AuditJson.write(req));try{var h=auth.headers(null);var resp=withOneAuthRetry(()->http.checkQrByMerchant(h.sessionToken(),h.accessToken(),req),()->{var h2=auth.headers(null);return http.checkQrByMerchant(h2.sessionToken(),h2.accessToken(),req);});String body=resp.getBody();var parsed=AuditJson.read(body,AyaGenericResponse.class);complete(id,resp,body,parsed==null?null:String.valueOf(parsed.err()),parsed==null?null:parsed.message(),null);return parsed;}catch(RuntimeException e){fail(id,e);throw e;}}
    /**
     * Executes the refund operation.
     *
     * @param paymentId the payment identifier
     * @param externalTransactionId the external transaction identifier value
     * @param referenceNumber the reference number value
     * @return the resulting {@code AyaGenericResponse} value
     */
    public AyaGenericResponse refund(UUID paymentId,String externalTransactionId,String referenceNumber){String path="/merchant/1.0.0/thirdparty/merchant/refundPayment";var req=new AyaRefundRequest(referenceNumber,externalTransactionId);UUID id=start(paymentId,"REFUND",externalTransactionId,path,AuditJson.write(req));try{var h=auth.headers(paymentId);var resp=withOneAuthRetry(()->http.refund(h.sessionToken(),h.accessToken(),req),()->{var h2=auth.headers(paymentId);return http.refund(h2.sessionToken(),h2.accessToken(),req);});String body=resp.getBody();var parsed=AuditJson.read(body,AyaGenericResponse.class);complete(id,resp,body,parsed==null?null:String.valueOf(parsed.err()),parsed==null?null:parsed.message(),referenceNumber);return parsed;}catch(RuntimeException e){fail(id,e);throw e;}}
    /**
     * Executes the start operation.
     *
     * @param paymentId the payment identifier
     * @param op the op value
     * @param orderId the provider or merchant order identifier
     * @param path the path value
     * @param body the request or response body
     * @return the resulting {@code UUID} value
     */
    private UUID start(UUID paymentId,String op,String orderId,String path,String body){return audit.start(paymentId,"AYAPAY",op,null,orderId,"POST",props.baseUrl()+path,AuditJson.write(Map.of("Authorization","***REDACTED***","Token","***REDACTED***")),body);}/**
     * Executes the complete operation.
     *
     * @param id the resource identifier
     * @param resp the resp value
     * @param body the request or response body
     * @param code the provider or status code
     * @param msg the response message
     * @param ref the ref value
     */
    private void complete(UUID id,ResponseEntity<String> resp,String body,String code,String msg,String ref){audit.complete(id,resp.getStatusCode().value(),AuditJson.write(resp.getHeaders()),body,code,msg,"RECEIVED",ref);}/**
     * Executes the fail operation.
     *
     * @param id the resource identifier
     * @param e the exception that caused the operation to fail
     */
    private void fail(UUID id,RuntimeException e){if(e instanceof HttpStatusCodeException h)audit.fail(id,h.getStatusCode().value(),AuditJson.write(h.getResponseHeaders()),h.getResponseBodyAsString(),h,h.getStatusCode().is5xxServerError()?"UNKNOWN":"REJECTED");else if(e instanceof ResourceAccessException)audit.fail(id,null,null,null,e,"UNKNOWN");else audit.fail(id,null,null,null,e,"ERROR");}/**
     * Executes the with one auth retry operation.
     *
     * @param first the first value
     * @param retry the retry value
     * @return the resulting {@code T} value
     */
    private <T>T withOneAuthRetry(Supplier<T> first,Supplier<T> retry){try{return first.get();}catch(HttpStatusCodeException e){if(e.getStatusCode().value()!=401)throw e;auth.invalidate();return retry.get();}}/**
     * Executes the mask operation.
     *
     * @param p the payment entity
     * @return the resulting mask text
     */
    private static String mask(String p){if(p==null||p.length()<4)return "***";return "***"+p.substring(p.length()-4);}
}
