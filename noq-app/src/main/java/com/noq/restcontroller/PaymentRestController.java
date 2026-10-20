package com.noq.restcontroller;

import com.noq.dto.MockPaymentRequest;
import com.noq.dto.PaymentResponse;
import com.noq.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/orders/{orderReference}/payments")
public class PaymentRestController {

    private final PaymentService paymentService;

    public PaymentRestController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** STAFF: the customer paid at the counter. */
    @PostMapping("/counter-confirmation")
    public PaymentResponse confirmCounterPayment(@PathVariable String orderReference, Principal staff) {
        return paymentService.confirmCounterPayment(orderReference, staff.getName());
    }

    /** CUSTOMER: SIMULATED online payment - {"success": true} or {"success": false}. No real money. */
    @PostMapping("/mock-online")
    public PaymentResponse mockOnlinePayment(@PathVariable String orderReference,
                                             @RequestHeader(OrderRestController.OTP_HEADER) String otp,
                                             @Valid @RequestBody MockPaymentRequest request) {
        return paymentService.payOnlineMock(orderReference, otp, request.success());
    }
}
