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
        assertTrue(PLACED.canMoveTo(CONFIRMED));
        assertTrue(CONFIRMED.canMoveTo(PREPARING));
        assertTrue(PREPARING.canMoveTo(READY));
        assertTrue(READY.canMoveTo(COMPLETED));
    }

    @Test
    void invalidTransitionsAreRejected() {
        assertFalse(PLACED.canMoveTo(READY), "cannot skip steps");
        assertFalse(COMPLETED.canMoveTo(PREPARING), "completed orders never go back");
        assertFalse(PREPARING.canMoveTo(CANCELLED), "no cancelling once cooking started");
        for (OrderStatus next : OrderStatus.values()) {
            assertFalse(CANCELLED.canMoveTo(next));
            assertFalse(COMPLETED.canMoveTo(next));
        }
    }

    @Test
    void completeTransitionMatrixIsExactlyTheDocumentedOne() {
        var allowed = java.util.Map.of(
                PLACED, java.util.Set.of(CONFIRMED, CANCELLED),
                CONFIRMED, java.util.Set.of(PREPARING, CANCELLED),
                PREPARING, java.util.Set.of(READY),
                READY, java.util.Set.of(COMPLETED),
                COMPLETED, java.util.Set.<OrderStatus>of(),
                CANCELLED, java.util.Set.<OrderStatus>of());
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                assertTrue(from.canMoveTo(to) == allowed.get(from).contains(to), from + " -> " + to);
            }
        }
    }
}
