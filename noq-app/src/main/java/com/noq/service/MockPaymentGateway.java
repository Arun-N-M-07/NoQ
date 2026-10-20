package com.noq.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SIMULATED payment gateway for the college demo. No money moves, no card/UPI details are collected,
 * and nothing is sent to any bank. The customer simply chooses "success" or "failure" on the demo page.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public ChargeResult charge(String orderReference, BigDecimal amount, boolean simulateSuccess) {
        String txn = "MOCK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        return simulateSuccess
                ? new ChargeResult(true, txn, "Simulated payment successful")
                : new ChargeResult(false, txn, "Simulated payment failed: payment declined");
    }
}
