package com.noq.service;

/** Published when an order becomes READY. Carries only the public order reference. */
public record OrderReadyEvent(String orderReference) {
}
