package com.noq.enums;

/**
 * Payment state of an order, and also the result stored on each payment record.
 * REFUNDED is set when a PAID order is cancelled (simulated refund).
 */
public enum PaymentStatus {
    PENDING, PAID, FAILED, REFUNDED
}
