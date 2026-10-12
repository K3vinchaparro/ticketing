package co.com.ticketing.model.order;

import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.ticket.TicketStatus;

import java.time.Instant;

public record Order(
        String orderId,
        String eventId,
        String customerId,
        int quantity,
        OrderStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public Order {
        requireText(orderId, "orderId");
        requireText(eventId, "eventId");
        requireText(customerId, "customerId");
        if (quantity < 1) {
            throw new InvalidDataException("quantity must be at least 1");
        }
        if (status == null || createdAt == null || updatedAt == null) {
            throw new InvalidDataException("status, createdAt and updatedAt are required");
        }
    }

    public static Order pending(String orderId, String eventId, String customerId, int quantity,
                                Instant now, Instant processingDeadline) {
        return new Order(orderId, eventId, customerId, quantity, OrderStatus.PENDING, processingDeadline, now, now);
    }

    public Order transitionTo(OrderStatus next, Instant now, Instant deadline) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidTransitionException(status, next);
        }
        return new Order(orderId, eventId, customerId, quantity, next, next.isFinal() ? null : deadline, createdAt, now);
    }

    public Order withDeadline(Instant deadline) {
        return new Order(orderId, eventId, customerId, quantity, status, deadline, createdAt, updatedAt);
    }

    public boolean isOverdue(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public TicketStatus ticketStatus() {
        return status.ticketStatus();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException(field + " is required");
        }
    }
}
