package com.noq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PickupRequest(
        @NotBlank(message = "otp is required")
        @Pattern(regexp = "\\d{6}", message = "otp must be exactly 6 digits") String otp) {
}
