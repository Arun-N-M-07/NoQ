package com.noq.entity;

import com.noq.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/** Money received at the canteen counter. Stored in the payment table with payment_type = 'CASH'. */
@Entity
@DiscriminatorValue("CASH")
public class CashPayment extends Payment {

    /** Username of the staff member who took the money. */
    @Column(name = "received_by", length = 50)
    private String receivedBy;

    protected CashPayment() {
    }

    public CashPayment(BigDecimal amount, String receivedBy) {
        super(amount, PaymentStatus.PAID);
        this.receivedBy = receivedBy;
    }

    @Override
    public String getPaymentType() {
        return "CASH";
    }

    public String getReceivedBy() { return receivedBy; }
}
