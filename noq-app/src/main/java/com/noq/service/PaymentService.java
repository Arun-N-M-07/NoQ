package com.noq.service;

import com.noq.dto.PaymentResponse;
import com.noq.entity.CashPayment;
import com.noq.entity.CustomerOrder;
import com.noq.entity.OnlinePayment;
import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentMethod;
import com.noq.enums.PaymentStatus;
import com.noq.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment rules. Payment status is tracked separately from food status:
 * an order can be PREPARING while its counter payment is still PENDING.
 */
@Service
public class PaymentService {

    private final OrderService orderService;
    private final PickupCodeService pickupCodes;
    private final PaymentGateway paymentGateway;

    public PaymentService(OrderService orderService, PickupCodeService pickupCodes, PaymentGateway paymentGateway) {
        this.orderService = orderService;
        this.pickupCodes = pickupCodes;
        this.paymentGateway = paymentGateway;
    }

    /** Staff received cash/UPI at the counter. Saved as a CashPayment row (payment_type = 'CASH'). */
    @Transactional
    public PaymentResponse confirmCounterPayment(String orderReference, String staffUsername) {
        CustomerOrder order = orderService.findForUpdate(orderReference);
        if (order.getPaymentMethod() != PaymentMethod.PAY_AT_COUNTER) {
            throw ApiException.badRequest("This order uses online payment, not counter payment");
        }
        ensurePayable(order);
        order.addPayment(new CashPayment(order.getTotalAmount(), staffUsername));
        order.setPaymentStatus(PaymentStatus.PAID);
        return new PaymentResponse(orderReference, "CASH", PaymentStatus.PAID, order.getTotalAmount(), null,
                "Counter payment received");
    }

    /** SIMULATED online payment. Every attempt (success or failure) is saved as an OnlinePayment row. */
    @Transactional
    public PaymentResponse payOnlineMock(String orderReference, String otp, boolean simulateSuccess) {
        CustomerOrder order = orderService.findForUpdate(orderReference);
        pickupCodes.checkCustomerAccess(order, otp);
        if (order.getPaymentMethod() != PaymentMethod.ONLINE) {
            throw ApiException.badRequest("This order is pay-at-counter; please pay at the canteen counter");
        }
        ensurePayable(order);
        if (orderService.isPaymentWindowOver(order)) {
            throw ApiException.conflict("The payment window for this order has closed; it will be cancelled. Please order again.");
        }

        PaymentGateway.ChargeResult result = paymentGateway.charge(orderReference, order.getTotalAmount(), simulateSuccess);
        PaymentStatus status = result.success() ? PaymentStatus.PAID : PaymentStatus.FAILED;
        order.addPayment(new OnlinePayment(order.getTotalAmount(), status, result.transactionReference(), paymentGateway.name()));
        order.setPaymentStatus(status);
        return new PaymentResponse(orderReference, "ONLINE", status, order.getTotalAmount(),
                result.transactionReference(), result.message());
    }

    /** Blocks double payments and payments for cancelled orders. */
    private void ensurePayable(CustomerOrder order) {
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            throw ApiException.conflict("Payment already received for this order");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("Order is cancelled");
        }
    }
}
