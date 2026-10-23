package com.noq.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Checkout request. The browser sends only item ids and quantities (payment is always at the counter).
 * Totals are always calculated on the server.
 */
public record PlaceOrderRequest(
        @NotEmpty(message = "Order must contain at least one item")
        @Size(max = 20, message = "At most 20 different items per order")
        List<@Valid @NotNull OrderLineRequest> items,
        @Size(max = 64) String idempotencyKey) {
}
