package com.noq.dto;

import jakarta.validation.constraints.NotNull;

/** SIMULATION ONLY: the demo page lets the user choose whether the fake payment succeeds or fails. */
public record MockPaymentRequest(@NotNull(message = "success is required (true or false)") Boolean success) {
}
