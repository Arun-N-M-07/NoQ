package com.noq.dto;

import com.noq.enums.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record StatusUpdateRequest(@NotNull(message = "status is required") OrderStatus status) {
}
