package com.noq.service;

import com.noq.dto.*;
import com.noq.entity.CustomerOrder;
import com.noq.entity.MenuItem;
import com.noq.entity.OrderItem;
import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentMethod;
import com.noq.enums.PaymentStatus;
import com.noq.exception.ApiException;
import com.noq.repository.CustomerOrderRepository;
import com.noq.repository.MenuItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The heart of NoQ: placing orders, moving them through their statuses, cancelling and pickup.
 * Every public method is {@code @Transactional}: either ALL its database changes are saved, or NONE are.
 */
@Service
public class OrderService {

    private static final String REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O or 1/I confusion
    private static final int MAX_QUANTITY_PER_ITEM = 20;
    // Orders still being worked on; COMPLETED and CANCELLED are finished. This is what the staff board shows.
    private static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY);

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final CustomerOrderRepository orderRepository;
    private final MenuItemRepository menuItemRepository;
    private final MenuService menuService;
    private final PickupCodeService pickupCodes;
    private final ApplicationEventPublisher events;
    private final boolean requirePaymentBeforeConfirm;
    private final Duration onlinePaymentTimeout;
    private final SecureRandom random = new SecureRandom();

    public OrderService(CustomerOrderRepository orderRepository, MenuItemRepository menuItemRepository,
                        MenuService menuService, PickupCodeService pickupCodes, ApplicationEventPublisher events,
                        @Value("${noq.orders.require-payment-before-confirm:false}") boolean requirePaymentBeforeConfirm,
                        @Value("${noq.orders.online-payment-timeout:15m}") Duration onlinePaymentTimeout) {
        this.orderRepository = orderRepository;
        this.menuItemRepository = menuItemRepository;
        this.menuService = menuService;
        this.pickupCodes = pickupCodes;
        this.events = events;
        this.requirePaymentBeforeConfirm = requirePaymentBeforeConfirm;
        this.onlinePaymentTimeout = onlinePaymentTimeout;
    }

    /**
     * Checkout. Reads current price and stock from the DATABASE (never from the cache or the browser),
     * deducts stock, and saves the order with its items in one transaction.
     * Menu rows are locked (FOR UPDATE) in ascending id order, so two customers buying the last samosa queue up:
     * the second one sees stock 0 and gets a clean 409. (@Version stays as a safety net.)
     */
    @Transactional
    public OrderPlacedResponse placeOrder(PlaceOrderRequest request) {
        String idempotencyKey = request.idempotencyKey();
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            idempotencyKey = null;
        }
        rejectIfAlreadySubmitted(idempotencyKey);

        CustomerOrder order = new CustomerOrder();
        Map<Long, Integer> quantities = mergeDuplicateLines(request.items());
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            addLineToOrder(order, entry.getKey(), entry.getValue());
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderItem line : order.getItems()) {
            subtotal = subtotal.add(line.getLineTotal());
        }
        order.setSubtotal(subtotal);
        order.setTotalAmount(subtotal); // no taxes or fees in the college canteen
        order.setPaymentMethod(request.paymentMethod());
        order.setIdempotencyKey(idempotencyKey);
        order.setOrderReference(newOrderReference());
        String otp = pickupCodes.newOtp();
        order.setPickupOtpHash(pickupCodes.hash(otp));
        orderRepository.save(order); // cascades to the order items

        menuService.evictMenuCaches(); // stock and popularity changed
        return new OrderPlacedResponse(order.getOrderReference(), otp, order.getStatus(), order.getPaymentMethod(),
                order.getPaymentStatus(), order.getTotalAmount(), confirmationMessage(order));
    }

    /** A repeated submission (double click, network retry) carries the same key and must not create a second order. */
    private void rejectIfAlreadySubmitted(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        Optional<CustomerOrder> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            throw ApiException.conflict("This order was already submitted as " + existing.get().getOrderReference());
        }
    }

    /** Merges repeated lines, e.g. [{1,x2},{1,x1}] becomes {1: 3}. TreeMap = sorted by id = same lock order for every buyer. */
    private Map<Long, Integer> mergeDuplicateLines(List<OrderLineRequest> lines) {
        Map<Long, Integer> quantities = new TreeMap<>();
        for (OrderLineRequest line : lines) {
            quantities.merge(line.menuItemId(), line.quantity(), Integer::sum);
        }
        return quantities;
    }

    /** Locks the menu item, checks it can be sold in this quantity, deducts the stock and adds the order line. */
    private void addLineToOrder(CustomerOrder order, Long menuItemId, int quantity) {
        MenuItem item = menuItemRepository.findWithLockById(menuItemId)
                .orElseThrow(() -> ApiException.badRequest("Menu item " + menuItemId + " does not exist"));

        if (quantity > MAX_QUANTITY_PER_ITEM) {
            throw ApiException.badRequest("At most " + MAX_QUANTITY_PER_ITEM + " x " + item.getName() + " per order");
        }
        if (!item.isActive()) {
            throw ApiException.conflict(item.getName() + " is no longer on the menu");
        }
        if (!item.isAvailable() || item.getAvailableQuantity() == 0) {
            throw ApiException.conflict(item.getName() + " is sold out");
        }
        if (quantity > item.getAvailableQuantity()) {
            throw ApiException.conflict("Only " + item.getAvailableQuantity() + " x " + item.getName() + " left");
        }

        item.reduceStock(quantity); // also increases popularityCount
        order.addItem(new OrderItem(item, quantity));
    }

    private String confirmationMessage(CustomerOrder order) {
        if (order.getPaymentMethod() == PaymentMethod.ONLINE) {
            return "Order placed. Complete the (simulated) online payment within " + onlinePaymentTimeout.toMinutes()
                    + " minutes, otherwise the order is cancelled automatically.";
        }
        return "Order placed! Pay ₹" + order.getTotalAmount() + " at the counter. Show your pickup code when collecting.";
    }

    @Transactional(readOnly = true)
    public OrderStatusResponse getStatus(String orderReference) {
        return OrderStatusResponse.from(find(orderReference));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderForCustomer(String orderReference, String otp) {
        CustomerOrder order = find(orderReference);
        pickupCodes.checkCustomerAccess(order, otp);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderForStaff(String orderReference) {
        return OrderResponse.from(find(orderReference));
    }

    /** No status = every OPEN order (not COMPLETED/CANCELLED), so none is hidden by newer ones; with status = HQL query 2. */
    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(OrderStatus status) {
        List<CustomerOrder> orders = status == null
                ? orderRepository.findByStatusInOrderByCreatedAtDesc(OPEN_STATUSES)
                : orderRepository.findByStatusNewestFirst(status);
        return orders.stream().map(OrderResponse::from).toList();
    }

    /** Staff moves an order forward (or cancels it). COMPLETED is only reachable through pickup verification. */
    @Transactional
    public OrderResponse updateStatus(String orderReference, OrderStatus newStatus) {
        CustomerOrder order = findForUpdate(orderReference);
        if (newStatus == OrderStatus.COMPLETED) {
            throw ApiException.badRequest("Orders are completed by verifying the customer's pickup OTP (verify-pickup)");
        }
        if (newStatus == OrderStatus.CANCELLED) {
            cancel(order);
            return OrderResponse.from(order);
        }
        OrderStatus current = order.getStatus();
        if (!current.canMoveTo(newStatus)) {
            throw ApiException.conflict("Cannot change order from " + current + " to " + newStatus);
        }
        if (newStatus == OrderStatus.CONFIRMED) {
            checkPaymentRulesForConfirm(order);
        }
        order.setStatus(newStatus);
        if (newStatus == OrderStatus.READY) {
            // Delivered to NotificationClient only AFTER this transaction commits successfully.
            events.publishEvent(new OrderReadyEvent(order.getOrderReference()));
        }
        return OrderResponse.from(order);
    }

    /** An ONLINE order must be paid before the kitchen starts; for counter orders this is optional (configurable). */
    private void checkPaymentRulesForConfirm(CustomerOrder order) {
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            return;
        }
        if (order.getPaymentMethod() == PaymentMethod.ONLINE) {
            throw ApiException.conflict("Online payment has not been completed for this order yet");
        }
        if (requirePaymentBeforeConfirm) {
            throw ApiException.conflict("Collect the counter payment before confirming this order");
        }
    }

    /** Customers may cancel their own order only while it is still PLACED (kitchen has not accepted it). */
    @Transactional
    public OrderResponse cancelByCustomer(String orderReference, String otp) {
        CustomerOrder order = findForUpdate(orderReference);
        pickupCodes.checkCustomerAccess(order, otp);
        if (order.getStatus() != OrderStatus.PLACED) {
            throw ApiException.conflict("Your order has already been accepted by the kitchen. Please ask at the counter.");
        }
        cancel(order);
        return OrderResponse.from(order);
    }

    /** Counter staff type the customer's OTP. Valid only once, only for a READY and PAID order. */
    @Transactional
    public OrderResponse verifyPickup(String orderReference, String otp) {
        CustomerOrder order = findForUpdate(orderReference);
        if (order.getStatus() == OrderStatus.COMPLETED) {
            throw ApiException.conflict("Order already collected at "
                    + order.getCompletedAt().format(DateTimeFormatter.ofPattern("hh:mm a")));
        }
        if (order.getStatus() != OrderStatus.READY) {
            throw ApiException.conflict("Order is not ready for pickup (status: " + order.getStatus() + ")");
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw ApiException.conflict("Payment pending: collect ₹" + order.getTotalAmount() + " at the counter first");
        }
        if (!pickupCodes.matches(otp, order.getPickupOtpHash())) {
            throw ApiException.badRequest("Invalid pickup OTP");
        }
        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(LocalDateTime.now());
        return OrderResponse.from(order);
    }

    /**
     * Abandoned ONLINE orders must not hold stock forever. Every minute, unpaid ONLINE orders still PLACED after
     * the payment window are cancelled through the same {@link #cancel} rules (stock restored exactly once).
     * One transaction for the whole batch: if a customer pays one of these orders at the same moment, the
     * {@code @Version} check makes this batch roll back and the next run simply skips the now-paid order.
     */
    @Scheduled(fixedDelayString = "${noq.orders.expiry-check-interval:PT1M}",
            initialDelayString = "${noq.orders.expiry-check-interval:PT1M}")
    @Transactional
    public int expireUnpaidOnlineOrders() {
        List<CustomerOrder> expired = orderRepository.findUnpaidOnlineOrdersPlacedBefore(paymentDeadlineCutoff());
        for (CustomerOrder order : expired) {
            cancel(order);
        }
        if (!expired.isEmpty()) {
            log.info("Cancelled {} unpaid online order(s) after {} minutes", expired.size(), onlinePaymentTimeout.toMinutes());
        }
        return expired.size();
    }

    /** True when an ONLINE order is older than the payment window (used to refuse late payments). */
    boolean isPaymentWindowOver(CustomerOrder order) {
        return order.getPaymentMethod() == PaymentMethod.ONLINE && order.getCreatedAt().isBefore(paymentDeadlineCutoff());
    }

    private LocalDateTime paymentDeadlineCutoff() {
        return LocalDateTime.now().minus(onlinePaymentTimeout);
    }

    /** Cancellation is allowed only before preparation starts; reserved stock goes back on the shelf. */
    private void cancel(CustomerOrder order) {
        if (!order.getStatus().canMoveTo(OrderStatus.CANCELLED)) {
            throw ApiException.conflict("Only PLACED or CONFIRMED orders can be cancelled (current: " + order.getStatus() + ")");
        }
        for (OrderItem line : order.getItems()) {
            line.getMenuItem().restoreStock(line.getQuantity());
        }
        order.setStatus(OrderStatus.CANCELLED);
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            order.setPaymentStatus(PaymentStatus.REFUNDED); // simulated refund
        }
        menuService.evictMenuCaches();
    }

    /** Locks the order row, so two actions on the same order (pay, cancel, confirm ...) run one after the other. */
    CustomerOrder findForUpdate(String orderReference) {
        return orderRepository.findWithLockByOrderReference(orderReference)
                .orElseThrow(() -> ApiException.notFound("Order " + orderReference + " not found"));
    }

    CustomerOrder find(String orderReference) {
        return orderRepository.findByOrderReference(orderReference)
                .orElseThrow(() -> ApiException.notFound("Order " + orderReference + " not found"));
    }

    /** NQ-2026-7KQ2M : year + 5 random characters. The UNIQUE column is the final guarantee. */
    private String newOrderReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder ref = new StringBuilder("NQ-").append(Year.now()).append('-');
            for (int i = 0; i < 5; i++) {
                ref.append(REFERENCE_ALPHABET.charAt(random.nextInt(REFERENCE_ALPHABET.length())));
            }
            if (!orderRepository.existsByOrderReference(ref.toString())) {
                return ref.toString();
            }
        }
        throw new IllegalStateException("Could not generate a unique order reference");
    }
}
