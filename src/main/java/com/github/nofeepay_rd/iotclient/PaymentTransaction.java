package com.github.nofeepay_rd.iotclient;

public class PaymentTransaction {

    private String transactionId;
    private String customerId;
    private String merchantId;
    private double amount;
    private String currency;
    private String timestamp;
    private String customerSignature;

    public PaymentTransaction() {
    }

    public PaymentTransaction(
            String transactionId,
            String customerId,
            String merchantId,
            double amount,
            String currency,
            String timestamp,
            String customerSignature) {

        this.transactionId = transactionId;
        this.customerId = customerId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.timestamp = timestamp;
        this.customerSignature = customerSignature;
    }

    // getters and setters
}