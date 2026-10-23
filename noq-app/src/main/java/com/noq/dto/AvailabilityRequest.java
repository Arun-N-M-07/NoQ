package com.noq.dto;

import jakarta.validation.constraints.NotNull;

/** PATCH body from the Kitchen page: {"available": false} = "Not available" (the dish stays on the menu, greyed out). */
public record AvailabilityRequest(@NotNull(message = "available is required (true or false)") Boolean available) {
}
