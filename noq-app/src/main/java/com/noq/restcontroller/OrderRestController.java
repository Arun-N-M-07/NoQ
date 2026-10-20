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
 * JSON API for orders. Customers identify themselves with the order reference + the pickup OTP,
 * sent in the {@code X-Pickup-Otp} header (headers are not stored in browser history or server URLs logs).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderRestController {

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

    /** Full details: staff see any order; a customer must send the matching OTP header. */
    @GetMapping("/{orderReference}")
    public OrderResponse getOrder(@PathVariable String orderReference,
                                  @RequestHeader(value = OTP_HEADER, required = false) String otp,
                                  Authentication authentication) {
        return isStaff(authentication)
                ? orderService.getOrderForStaff(orderReference)
                : orderService.getOrderForCustomer(orderReference, otp);
    }

    /** Lightweight public status for polling every few seconds. */
    @GetMapping("/{orderReference}/status")
    public OrderStatusResponse getStatus(@PathVariable String orderReference) {
        return orderService.getStatus(orderReference);
    }

    /** STAFF: GET /api/orders or GET /api/orders?status=PLACED */
    @GetMapping
    public List<OrderResponse> listOrders(@RequestParam(required = false) OrderStatus status) {
        return orderService.listOrders(status);
    }

    /** STAFF: {"status": "PREPARING"} */
    @PatchMapping("/{orderReference}/status")
    public OrderResponse updateStatus(@PathVariable String orderReference, @Valid @RequestBody StatusUpdateRequest request) {
        return orderService.updateStatus(orderReference, request.status());
    }

    /** STAFF: {"otp": "123456"} - completes a READY, PAID order exactly once. */
    @PostMapping("/{orderReference}/verify-pickup")
    public OrderResponse verifyPickup(@PathVariable String orderReference, @Valid @RequestBody PickupRequest request) {
        return orderService.verifyPickup(orderReference, request.otp());
    }

    /** CUSTOMER: cancel own order while it is still PLACED. */
    @PostMapping("/{orderReference}/cancel")
    public OrderResponse cancel(@PathVariable String orderReference, @RequestHeader(OTP_HEADER) String otp) {
        return orderService.cancelByCustomer(orderReference, otp);
    }

    private static boolean isStaff(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_STAFF".equals(a.getAuthority()));
    }
}
