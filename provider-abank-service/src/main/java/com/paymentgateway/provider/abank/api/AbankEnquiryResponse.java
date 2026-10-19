package com.paymentgateway.provider.abank.api;

import java.math.BigDecimal;

/**
 * Carries response data for abank enquiry operations.
 */
public record AbankEnquiryResponse(Data data,String respondMessage,Integer respondCode){
    /**
     * Immutable data carrier for data.
     */
    public record Data(
            String orderId,String merchantId,String currency,BigDecimal amount,Integer rewardPoint,String description,
            String createdDate,String txnStatusDescription,String posTransactionId,String merchantName,String billNo,
            String endToEndId,String customerName,String customerPhone,BigDecimal fee,String reason,String paymentTxnID,
            Integer paymentTxnStatus,String transactionDateTime,String institutionName
    ){}
}
