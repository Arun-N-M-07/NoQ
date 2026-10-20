package com.noq.dto;

import com.noq.entity.CustomerOrder;
import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentMethod;
import com.noq.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Full order details. Deliberately has NO pickup OTP field. */
public record OrderResponse(
        String orderReference,
        OrderStatus status,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        BigDecimal subtotal,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        List<OrderItemResponse> items) {

    public static OrderResponse from(CustomerOrder o) {
        return new OrderResponse(o.getOrderReference(), o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(),
                o.getSubtotal(), o.getTotalAmount(), o.getCreatedAt(), o.getUpdatedAt(), o.getCompletedAt(),
                o.getItems().stream().map(OrderItemResponse::from).toList());
    }
}
