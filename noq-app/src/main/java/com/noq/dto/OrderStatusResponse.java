package com.noq.dto;

import com.noq.entity.CustomerOrder;
import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentMethod;
import com.noq.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Small public status object used for polling. Contains nothing secret. */
public record OrderStatusResponse(
        String orderReference,
        OrderStatus status,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        BigDecimal totalAmount,
        LocalDateTime updatedAt) {

    public static OrderStatusResponse from(CustomerOrder o) {
        return new OrderStatusResponse(o.getOrderReference(), o.getStatus(), o.getPaymentMethod(),
                o.getPaymentStatus(), o.getTotalAmount(), o.getUpdatedAt());
    }
}
