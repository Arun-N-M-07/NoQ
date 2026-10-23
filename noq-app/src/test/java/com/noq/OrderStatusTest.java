package com.noq;

import com.noq.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static com.noq.enums.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit test (no Spring): the order life-cycle rules. */
class OrderStatusTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertTrue(PREPARING.canMoveTo(READY_FOR_PICKUP));
        assertTrue(READY_FOR_PICKUP.canMoveTo(COLLECTED));
    }

    @Test
    void invalidTransitionsAreRejected() {
        assertFalse(PREPARING.canMoveTo(COLLECTED), "cannot skip READY_FOR_PICKUP");
        assertFalse(COLLECTED.canMoveTo(PREPARING), "collected orders never go back");
        assertFalse(READY_FOR_PICKUP.canMoveTo(CANCELLED), "no cancelling once the food is ready");
        for (OrderStatus next : OrderStatus.values()) {
            assertFalse(CANCELLED.canMoveTo(next));
            assertFalse(COLLECTED.canMoveTo(next));
        }
    }

    @Test
    void completeTransitionMatrixIsExactlyTheDocumentedOne() {
        var allowed = java.util.Map.of(
                PREPARING, java.util.Set.of(READY_FOR_PICKUP, CANCELLED),
                READY_FOR_PICKUP, java.util.Set.of(COLLECTED),
                COLLECTED, java.util.Set.<OrderStatus>of(),
                CANCELLED, java.util.Set.<OrderStatus>of());
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                assertTrue(from.canMoveTo(to) == allowed.get(from).contains(to), from + " -> " + to);
            }
        }
    }
}
