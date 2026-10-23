package com.noq.enums;

/**
 * Payment state of an order, and also the result stored on each payment record.
 * PENDING until staff take the money at the counter (PAID). REFUNDED is set when a PAID order is cancelled.
 */
public enum PaymentStatus {
    PENDING, PAID, REFUNDED
}
