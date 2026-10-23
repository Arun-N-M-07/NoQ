package com.noq.dto;

import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Returned ONCE, to the customer who placed the order, and shown on the order-confirmation screen.
 * This is the only response that ever contains the plain pickup OTP - the database keeps only its hash.
 */
public record OrderPlacedResponse(
        String orderReference,
        String pickupOtp,
        OrderStatus status,
        PaymentStatus paymentStatus,
        BigDecimal totalAmount,
        String message,
        List<OrderItemResponse> items) {
}
