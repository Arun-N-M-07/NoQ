package com.noq.dto;

import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentMethod;
import com.noq.enums.PaymentStatus;

import java.math.BigDecimal;

/**
 * Returned ONCE, to the customer who placed the order. This is the only response that ever
 * contains the plain pickup OTP - the database keeps only its hash.
 */
public record OrderPlacedResponse(
        String orderReference,
        String pickupOtp,
        OrderStatus status,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        BigDecimal totalAmount,
        String message) {
}
