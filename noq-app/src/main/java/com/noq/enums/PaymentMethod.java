package com.noq.enums;

/**
 * How the customer pays. NoQ supports paying at the canteen counter only; the value is still stored
 * on every order (customer_order.payment_method) so the table keeps the same shape.
 */
public enum PaymentMethod {
    PAY_AT_COUNTER
}
