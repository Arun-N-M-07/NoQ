package com.noq.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** PATCH body: send only what changes, e.g. {"availableQuantity": 40} or {"available": false}. */
public record AvailabilityRequest(
        @Min(value = 0, message = "Stock cannot be negative") @Max(10000) Integer availableQuantity,
        Boolean available) {
}
