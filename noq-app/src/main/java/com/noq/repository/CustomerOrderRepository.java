package com.noq.repository;

import com.noq.entity.CustomerOrder;
import com.noq.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    // "Derived queries": Spring Data builds the query from the method name.
    Optional<CustomerOrder> findByOrderReference(String orderReference);

    /** SELECT ... FOR UPDATE: every action that changes an order locks its row first (see OrderService.findForUpdate). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CustomerOrder> findWithLockByOrderReference(String orderReference);

    Optional<CustomerOrder> findByIdempotencyKey(String idempotencyKey);

    boolean existsByOrderReference(String orderReference);

    /** HQL QUERY 2 - orders with a given status, newest first (staff dashboard filter). */
    @Query("select o from CustomerOrder o where o.status = :status order by o.createdAt desc")
    List<CustomerOrder> findByStatusNewestFirst(@Param("status") OrderStatus status);

    /** Unpaid ONLINE orders still waiting for payment that were placed before the cutoff (see OrderService expiry). */
    @Query("""
            select o from CustomerOrder o
            where o.paymentMethod = com.noq.enums.PaymentMethod.ONLINE
              and o.status = com.noq.enums.OrderStatus.PLACED
              and o.paymentStatus <> com.noq.enums.PaymentStatus.PAID
              and o.createdAt < :cutoff""")
    List<CustomerOrder> findUnpaidOnlineOrdersPlacedBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Staff dashboard: all orders in the given (open) statuses, newest first. */
    List<CustomerOrder> findByStatusInOrderByCreatedAtDesc(Collection<OrderStatus> statuses);
}
