package com.noq.service;

import com.noq.dto.PaymentResponse;
import com.noq.entity.CashPayment;
import com.noq.entity.CustomerOrder;
import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentStatus;
import com.noq.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment rules. Customers pay at the counter; staff record it here.
 * Payment status is tracked separately from food status: an order can be PREPARING while its payment is still PENDING.
 */
@Service
public class PaymentService {

    private final OrderService orderService;

    public PaymentService(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Staff received cash/UPI at the counter. Saved as a CashPayment row (payment_type = 'CASH'). */
    @Transactional
    public PaymentResponse confirmCounterPayment(String orderReference, String staffUsername) {
        CustomerOrder order = orderService.findForUpdate(orderReference);
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            throw ApiException.conflict("Payment already received for this order");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("Order is cancelled");
        }
        order.addPayment(new CashPayment(order.getTotalAmount(), staffUsername));
        order.setPaymentStatus(PaymentStatus.PAID);
        return new PaymentResponse(orderReference, "CASH", PaymentStatus.PAID, order.getTotalAmount(), "Counter payment received");
    }
}
