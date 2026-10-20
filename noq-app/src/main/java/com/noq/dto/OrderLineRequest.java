package com.noq.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** One cart line sent by the browser. Note: NO price - the server looks prices up itself. */
public record OrderLineRequest(
        @NotNull(message = "menuItemId is required") Long menuItemId,
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 20, message = "quantity must be at most 20") Integer quantity) {
}
