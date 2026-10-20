package com.noq.entity;

import com.noq.enums.PaymentStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * INHERITANCE MAPPING demo.
 * <p>
 * Payment is abstract; the concrete kinds are {@link CashPayment} and {@link OnlinePayment}.
 * With {@code InheritanceType.SINGLE_TABLE} Hibernate stores every subclass in ONE table called
 * {@code payment}. The extra column {@code payment_type} (the discriminator) tells Hibernate which
 * Java class to create when it reads a row: "CASH" -> CashPayment, "ONLINE" -> OnlinePayment.
 * Columns that belong to only one subclass (e.g. transaction_reference) are NULL for the other.
 * <p>
 * Why SINGLE_TABLE? No joins, fastest queries, simplest schema. Trade-off: subclass columns must be nullable.
 */
@Entity
@Table(name = "payment")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "payment_type", length = 20)
public abstract class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder customerOrder;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** PAID (successful) or FAILED for this single attempt. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)") // plain VARCHAR: no MySQL ENUM/CHECK, so a new enum value needs no ALTER TABLE
    private PaymentStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Payment() {
    }

    protected Payment(BigDecimal amount, PaymentStatus status) {
        this.amount = amount;
        this.status = status;
    }

    /** The discriminator value, e.g. "CASH" or "ONLINE". */
    public abstract String getPaymentType();

    public Long getId() { return id; }
    public CustomerOrder getCustomerOrder() { return customerOrder; }
    void setCustomerOrder(CustomerOrder customerOrder) { this.customerOrder = customerOrder; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
