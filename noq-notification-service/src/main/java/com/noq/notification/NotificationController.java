package com.noq.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * REST endpoints of the notification microservice.
 * Notifications are only logged and kept in memory (lost on restart) - enough for a demo.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);
    private static final int MAX_STORED = 100;

    /** Incoming JSON payload. Only an order reference and an event type - no personal data. */
    public record NotificationRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = "NQ-\\d{4}-[A-Z0-9]{5}", message = "must look like NQ-2026-AB12C")
            String orderReference,
            @NotBlank @Pattern(regexp = "ORDER_READY", message = "only ORDER_READY is supported")
            String eventType) {
    }

    /** JSON acknowledgement sent back to the main application. */
    public record NotificationAck(String notificationId, String orderReference, String eventType,
                                  String status, Instant receivedAt) {
    }

    // Thread-safe in-memory store; newest first.
    private final Deque<NotificationAck> received = new ConcurrentLinkedDeque<>();

    @PostMapping("/order-ready")
    public ResponseEntity<NotificationAck> orderReady(@Valid @RequestBody NotificationRequest request) {
        NotificationAck ack = new NotificationAck(UUID.randomUUID().toString(), request.orderReference(),
                request.eventType(), "RECEIVED", Instant.now());
        received.addFirst(ack);
        while (received.size() > MAX_STORED) {
            received.pollLast();
        }
        // In a real system this is where an SMS / e-mail / push notification would be sent.
        log.info("NOTIFY customer: order {} is READY for pickup", request.orderReference());
        return ResponseEntity.status(HttpStatus.CREATED).body(ack);
    }

    @GetMapping
    public List<NotificationAck> list() {
        return new ArrayList<>(received);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of("status", 400, "error", "Validation failed", "fieldErrors", errors));
    }
}
