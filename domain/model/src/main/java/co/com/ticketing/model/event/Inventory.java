package co.com.ticketing.model.event;

import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.ticket.TicketStatus;

public record Inventory(int available, int reserved, int pendingConfirmation, int sold, int complimentary) {

    public Inventory {
        requireNonNegative(available, "available");
        requireNonNegative(reserved, "reserved");
        requireNonNegative(pendingConfirmation, "pendingConfirmation");
        requireNonNegative(sold, "sold");
        requireNonNegative(complimentary, "complimentary");
    }

    public static Inventory ofCapacity(int capacity) {
        return new Inventory(capacity, 0, 0, 0, 0);
    }

    public int capacity() {
        return available + reserved + pendingConfirmation + sold + complimentary;
    }

    public int count(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> available;
            case RESERVED -> reserved;
            case PENDING_CONFIRMATION -> pendingConfirmation;
            case SOLD -> sold;
            case COMPLIMENTARY -> complimentary;
        };
    }

    public Inventory move(TicketStatus from, TicketStatus to, int quantity) {
        if (quantity < 1) {
            throw new InvalidDataException("Quantity must be at least 1");
        }
        if (from.isFinal() || from == to) {
            throw new InvalidTransitionException(from, to);
        }
        if (count(from) < quantity) {
            throw new InsufficientTicketsException(quantity);
        }
        return with(from, count(from) - quantity).with(to, count(to) + quantity);
    }

    private Inventory with(TicketStatus status, int value) {
        return switch (status) {
            case AVAILABLE -> new Inventory(value, reserved, pendingConfirmation, sold, complimentary);
            case RESERVED -> new Inventory(available, value, pendingConfirmation, sold, complimentary);
            case PENDING_CONFIRMATION -> new Inventory(available, reserved, value, sold, complimentary);
            case SOLD -> new Inventory(available, reserved, pendingConfirmation, value, complimentary);
            case COMPLIMENTARY -> new Inventory(available, reserved, pendingConfirmation, sold, value);
        };
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new InvalidDataException(field + " cannot be negative");
        }
    }
}
