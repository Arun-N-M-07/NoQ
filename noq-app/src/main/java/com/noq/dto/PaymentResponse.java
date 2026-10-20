package com.noq.dto;

import com.noq.enums.PaymentStatus;

import java.math.BigDecimal;

public record PaymentResponse(
        String orderReference,
        String paymentType,
        PaymentStatus paymentStatus,
        BigDecimal amount,
        String transactionReference,
        String message) {
}
