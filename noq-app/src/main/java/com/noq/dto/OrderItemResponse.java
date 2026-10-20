package com.noq.dto;

import com.noq.entity.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
    public static OrderItemResponse from(OrderItem i) {
        return new OrderItemResponse(i.getItemNameSnapshot(), i.getUnitPriceSnapshot(), i.getQuantity(), i.getLineTotal());
    }
}
