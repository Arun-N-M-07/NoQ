package com.noq.entity;

import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentMethod;
import com.noq.enums.PaymentStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An order placed by a customer. Named CustomerOrder because ORDER is a reserved SQL word.
 * <p>
 * Relationships: one order has many {@link OrderItem}s and many {@link Payment} attempts.
 */
@Entity
@Table(name = "customer_order", indexes = @Index(name = "idx_order_status_created", columnList = "status, created_at"))
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public order number such as NQ-2026-7KQ2M. The UNIQUE constraint guarantees no duplicates. */
    @Column(name = "order_reference", nullable = false, unique = true, length = 20)
    private String orderReference;

    /** BCrypt hash of the 6-digit pickup OTP. The plain OTP is never stored or logged. */
    @Column(name = "pickup_otp_hash", nullable = false, length = 100)
    private String pickupOtpHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)") // plain VARCHAR: no MySQL ENUM/CHECK, so a new enum value needs no ALTER TABLE
    private OrderStatus status = OrderStatus.PREPARING; // the kitchen starts on every new order

    /** Always PAY_AT_COUNTER (the only payment option); kept so the existing NOT NULL column is still filled. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, columnDefinition = "varchar(20)")
    private PaymentMethod paymentMethod = PaymentMethod.PAY_AT_COUNTER;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, columnDefinition = "varchar(20)")
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /** Random key sent by the browser per checkout; a repeated submission with the same key is rejected. */
    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    @CreationTimestamp // Hibernate fills this on INSERT
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp // Hibernate refreshes this on every UPDATE
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Stops two staff members from changing the same order at the same moment. */
    @Version
    private Long version;

    // mappedBy = the field in OrderItem that owns the foreign key; cascade = save items together with the order
    @OneToMany(mappedBy = "customerOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "customerOrder", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<Payment> payments = new ArrayList<>();

    /** Keeps both sides of the bidirectional relationship in sync. */
    public void addItem(OrderItem item) {
        item.setCustomerOrder(this);
        items.add(item);
    }

    public void addPayment(Payment payment) {
        payment.setCustomerOrder(this);
        payments.add(payment);
    }

    public Long getId() { return id; }
    public String getOrderReference() { return orderReference; }
    public void setOrderReference(String orderReference) { this.orderReference = orderReference; }
    public String getPickupOtpHash() { return pickupOtpHash; }
    public void setPickupOtpHash(String pickupOtpHash) { this.pickupOtpHash = pickupOtpHash; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public List<OrderItem> getItems() { return items; }
    public List<Payment> getPayments() { return payments; }
}
