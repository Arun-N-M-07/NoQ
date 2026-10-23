package com.noq.service;

import com.noq.dto.*;
import com.noq.entity.CustomerOrder;
import com.noq.entity.MenuItem;
import com.noq.entity.OrderItem;
import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentStatus;
import com.noq.exception.ApiException;
import com.noq.repository.CustomerOrderRepository;
import com.noq.repository.MenuItemRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
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
    // Orders still being worked on; COLLECTED and CANCELLED are finished.
    private static final List<OrderStatus> OPEN_STATUSES = List.of(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP);

    private final CustomerOrderRepository orderRepository;
    private final MenuItemRepository menuItemRepository;
    private final MenuService menuService;
    private final PickupCodeService pickupCodes;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public OrderService(CustomerOrderRepository orderRepository, MenuItemRepository menuItemRepository,
                        MenuService menuService, PickupCodeService pickupCodes, ApplicationEventPublisher events) {
        this.orderRepository = orderRepository;
        this.menuItemRepository = menuItemRepository;
        this.menuService = menuService;
        this.pickupCodes = pickupCodes;
        this.events = events;
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
        order.setIdempotencyKey(idempotencyKey);
        order.setOrderReference(newOrderReference());
        String otp = pickupCodes.newOtp();
        order.setPickupOtpHash(pickupCodes.hash(otp));
        orderRepository.save(order); // cascades to the order items

        menuService.evictMenuCaches(); // stock and popularity changed
        String message = "Order placed! Pay ₹" + order.getTotalAmount() + " at the counter. Show your pickup code when collecting.";
        return new OrderPlacedResponse(order.getOrderReference(), otp, order.getStatus(),
                order.getPaymentStatus(), order.getTotalAmount(), message,
                order.getItems().stream().map(OrderItemResponse::from).toList());
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
        if (!item.isAvailable()) {
            throw ApiException.conflict(item.getName() + " is not available right now");
        }
        if (item.getAvailableQuantity() == 0) {
            throw ApiException.conflict(item.getName() + " is sold out");
        }
        if (quantity > item.getAvailableQuantity()) {
            throw ApiException.conflict("Only " + item.getAvailableQuantity() + " x " + item.getName() + " left");
        }

        item.reduceStock(quantity); // also increases popularityCount
        order.addItem(new OrderItem(item, quantity));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderReference) {
        return OrderResponse.from(find(orderReference));
    }

    /** The customer's open confirmation screen re-reads its order; the pickup OTP proves it is theirs (5-attempt limit). */
    @Transactional(readOnly = true)
    public OrderResponse getOrderForCustomer(String orderReference, String otp) {
        CustomerOrder order = find(orderReference);
        pickupCodes.checkCustomerAccess(order, otp);
        return OrderResponse.from(order);
    }

    /** With a status = HQL query 2 (Kitchen page: PREPARING, Staff page: READY_FOR_PICKUP); no status = every open order. */
    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(OrderStatus status) {
        List<CustomerOrder> orders = status == null
                ? orderRepository.findByStatusInOrderByCreatedAtDesc(OPEN_STATUSES)
                : orderRepository.findByStatusNewestFirst(status);
        return orders.stream().map(OrderResponse::from).toList();
    }

    /** Kitchen marks an order READY_FOR_PICKUP (or it is cancelled). COLLECTED is only reachable through {@link #collect}. */
    @Transactional
    public OrderResponse updateStatus(String orderReference, OrderStatus newStatus) {
        CustomerOrder order = findForUpdate(orderReference);
        if (newStatus == OrderStatus.COLLECTED) {
            throw ApiException.badRequest("Orders are collected by verifying the customer's pickup OTP (collect)");
        }
        if (newStatus == OrderStatus.CANCELLED) {
            cancel(order);
            return OrderResponse.from(order);
        }
        OrderStatus current = order.getStatus();
        if (!current.canMoveTo(newStatus)) {
            throw ApiException.conflict("Cannot change order from " + current + " to " + newStatus);
        }
        order.setStatus(newStatus);
        if (newStatus == OrderStatus.READY_FOR_PICKUP) {
            // Delivered to NotificationClient only AFTER this transaction commits successfully.
            events.publishEvent(new OrderReadyEvent(order.getOrderReference()));
        }
        return OrderResponse.from(order);
    }

    /** Step 1 at the counter: staff check the customer's OTP (before taking the money). Changes nothing. */
    @Transactional(readOnly = true)
    public OrderResponse verifyPickup(String orderReference, String otp) {
        CustomerOrder order = find(orderReference);
        checkPickup(order, otp);
        return OrderResponse.from(order);
    }

    /** Step 2: staff confirm the handover. The OTP is checked again (the server never trusts step 1), the order must be PAID, then COLLECTED is saved once. */
    @Transactional
    public OrderResponse collect(String orderReference, String otp) {
        CustomerOrder order = findForUpdate(orderReference);
        checkPickup(order, otp);
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw ApiException.conflict("Payment pending: collect ₹" + order.getTotalAmount() + " at the counter first");
        }
        order.setStatus(OrderStatus.COLLECTED);
        order.setCompletedAt(LocalDateTime.now());
        return OrderResponse.from(order);
    }

    /** An order can be handed over only once, only when READY_FOR_PICKUP, and only with its own OTP (collect also needs PAID). */
    private void checkPickup(CustomerOrder order, String otp) {
        if (order.getStatus() == OrderStatus.COLLECTED) {
            throw ApiException.conflict("Order already collected at "
                    + order.getCompletedAt().format(DateTimeFormatter.ofPattern("hh:mm a")));
        }
        if (order.getStatus() != OrderStatus.READY_FOR_PICKUP) {
            throw ApiException.conflict("Order is not ready for pickup (status: " + order.getStatus() + ")");
        }
        if (!pickupCodes.matches(otp, order.getPickupOtpHash())) {
            throw ApiException.badRequest("Invalid pickup OTP");
        }
    }

    /** Cancellation (API only) is allowed while the order is still being prepared; reserved stock goes back on the shelf. */
    private void cancel(CustomerOrder order) {
        if (!order.getStatus().canMoveTo(OrderStatus.CANCELLED)) {
            throw ApiException.conflict("Only orders still being prepared can be cancelled (current: " + order.getStatus() + ")");
        }
        for (OrderItem line : order.getItems()) {
            line.getMenuItem().restoreStock(line.getQuantity());
        }
        order.setStatus(OrderStatus.CANCELLED);
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            order.setPaymentStatus(PaymentStatus.REFUNDED); // the counter gives the money back
        }
        menuService.evictMenuCaches();
    }

    /** Locks the order row, so two actions on the same order (pay, cancel, status change ...) run one after the other. */
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
