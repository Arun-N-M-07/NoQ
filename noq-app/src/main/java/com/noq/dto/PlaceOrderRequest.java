package com.noq.dto;

import com.noq.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Checkout request. The browser sends only item ids, quantities and the payment method.
 * Totals are always calculated on the server.
 */
public record PlaceOrderRequest(
        @NotEmpty(message = "Order must contain at least one item")
        @Size(max = 20, message = "At most 20 different items per order")
        List<@Valid @NotNull OrderLineRequest> items,
        @NotNull(message = "paymentMethod is required (PAY_AT_COUNTER or ONLINE)") PaymentMethod paymentMethod,
        @Size(max = 64) String idempotencyKey) {
}
