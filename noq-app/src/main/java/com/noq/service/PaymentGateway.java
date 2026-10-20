package com.noq.service;

import java.math.BigDecimal;

/**
 * The seam for online payments. Today the only implementation is {@link MockPaymentGateway}.
 * To use a real provider (Razorpay, Stripe, PayU ...) write another class implementing this interface:
 * it would create a payment session with the provider and confirm the result via the provider's callback.
 * The rest of the application (PaymentService, controllers) would not change.
 */
public interface PaymentGateway {

    record ChargeResult(boolean success, String transactionReference, String message) {
    }

    String name();

    /** {@code simulateSuccess} exists only for the demo gateway; a real gateway decides the outcome itself. */
    ChargeResult charge(String orderReference, BigDecimal amount, boolean simulateSuccess);
}
