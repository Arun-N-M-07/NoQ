package com.noq.entity;

import com.noq.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/** An online payment attempt. Stored in the payment table with payment_type = 'ONLINE'. */
@Entity
@DiscriminatorValue("ONLINE")
public class OnlinePayment extends Payment {

    /** Reference returned by the gateway. In this project the gateway is SIMULATED ("MOCK-..."). */
    @Column(name = "transaction_reference", length = 40)
    private String transactionReference;

    @Column(name = "gateway", length = 30)
    private String gateway;

    protected OnlinePayment() {
    }

    public OnlinePayment(BigDecimal amount, PaymentStatus status, String transactionReference, String gateway) {
        super(amount, status);
        this.transactionReference = transactionReference;
        this.gateway = gateway;
    }

    @Override
    public String getPaymentType() {
        return "ONLINE";
    }

    public String getTransactionReference() { return transactionReference; }
    public String getGateway() { return gateway; }
}
