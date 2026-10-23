package com.noq.restcontroller;

import com.noq.dto.*;
import com.noq.enums.OrderStatus;
import com.noq.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * JSON API for orders. Customers place an order and read it back with its pickup OTP; everything else is for STAFF
 * (see SecurityConfig). Staff record counter payments in {@link PaymentRestController}.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderRestController {

    /** Customers prove an order is theirs with its pickup OTP, sent in this header (headers are not stored in browser history or URL logs). */
    static final String OTP_HEADER = "X-Pickup-Otp";

    private final OrderService orderService;

    public OrderRestController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** Checkout: 201 Created with the order reference and the one-time display of the pickup OTP. */
    @PostMapping
    public ResponseEntity<OrderPlacedResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderPlacedResponse placed = orderService.placeOrder(request);
        return ResponseEntity.created(URI.create("/api/orders/" + placed.orderReference())).body(placed);
    }

    /** Full details (never the OTP): staff see any order; a customer's confirmation screen must send the order's OTP header. */
    @GetMapping("/{orderReference}")
    public OrderResponse getOrder(@PathVariable String orderReference,
                                  @RequestHeader(value = OTP_HEADER, required = false) String otp,
                                  Authentication authentication) {
        boolean staff = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_STAFF".equals(a.getAuthority()));
        return staff ? orderService.getOrder(orderReference) : orderService.getOrderForCustomer(orderReference, otp);
    }

    /** STAFF: GET /api/orders?status=PREPARING (Kitchen page), ?status=READY_FOR_PICKUP (Staff page), or all open orders */
    @GetMapping
    public List<OrderResponse> listOrders(@RequestParam(required = false) OrderStatus status) {
        return orderService.listOrders(status);
    }

    /** STAFF (Kitchen page): {"status": "READY_FOR_PICKUP"} */
    @PatchMapping("/{orderReference}/status")
    public OrderResponse updateStatus(@PathVariable String orderReference, @Valid @RequestBody StatusUpdateRequest request) {
        return orderService.updateStatus(orderReference, request.status());
    }

    /** STAFF: {"otp": "123456"} - checks the OTP of a READY_FOR_PICKUP order; changes nothing. */
    @PostMapping("/{orderReference}/verify-pickup")
    public OrderResponse verifyPickup(@PathVariable String orderReference, @Valid @RequestBody PickupRequest request) {
        return orderService.verifyPickup(orderReference, request.otp());
    }

    /** STAFF: {"otp": "123456"} - confirms the handover: checks the OTP again and saves COLLECTED, exactly once. */
    @PostMapping("/{orderReference}/collect")
    public OrderResponse collect(@PathVariable String orderReference, @Valid @RequestBody PickupRequest request) {
        return orderService.collect(orderReference, request.otp());
    }
}
