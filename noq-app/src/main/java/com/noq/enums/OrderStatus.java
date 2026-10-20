package com.noq.enums;

/**
 * Food-preparation status of an order (kept separate from {@link PaymentStatus}).
 * <pre>
 * PLACED -> CONFIRMED -> PREPARING -> READY -> COMPLETED
 *    \__________\______________________________> CANCELLED (only before preparation starts)
 * </pre>
 */
public enum OrderStatus {
    PLACED, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED;

    /** The single place that defines which status changes are legal. */
    public boolean canMoveTo(OrderStatus next) {
        return switch (this) {
            case PLACED -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == PREPARING || next == CANCELLED;
            case PREPARING -> next == READY;
            case READY -> next == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
