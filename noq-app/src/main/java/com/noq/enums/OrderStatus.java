package com.noq.enums;

/**
 * Food status of an order (kept separate from {@link PaymentStatus}).
 * <pre>
 * PREPARING -> READY_FOR_PICKUP -> COLLECTED
 *     \______> CANCELLED (only while still being prepared)
 * </pre>
 * The kitchen moves an order to READY_FOR_PICKUP; staff move it to COLLECTED after checking the pickup OTP.
 */
public enum OrderStatus {
    PREPARING, READY_FOR_PICKUP, COLLECTED, CANCELLED;

    /** The single place that defines which status changes are legal. */
    public boolean canMoveTo(OrderStatus next) {
        return switch (this) {
            case PREPARING -> next == READY_FOR_PICKUP || next == CANCELLED;
            case READY_FOR_PICKUP -> next == COLLECTED;
            case COLLECTED, CANCELLED -> false;
        };
    }
}
