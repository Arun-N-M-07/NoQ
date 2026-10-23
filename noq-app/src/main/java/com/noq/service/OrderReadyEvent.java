package com.noq.service;

/** Published when the kitchen marks an order READY_FOR_PICKUP. Carries only the public order reference. */
public record OrderReadyEvent(String orderReference) {
}
