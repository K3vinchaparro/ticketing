package co.com.ticketing.model.order;

import co.com.ticketing.model.ticket.TicketStatus;

public enum OrderStatus {
    PENDING,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    REJECTED,
    RELEASED;

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING -> next == RESERVED || next == REJECTED;
            case RESERVED -> next == PENDING_CONFIRMATION || next == RELEASED;
            case PENDING_CONFIRMATION -> next == SOLD || next == RELEASED;
            case SOLD, REJECTED, RELEASED -> false;
        };
    }

    public boolean isFinal() {
        return this == SOLD || this == REJECTED || this == RELEASED;
    }

    public TicketStatus ticketStatus() {
        return switch (this) {
            case PENDING, REJECTED, RELEASED -> TicketStatus.AVAILABLE;
            case RESERVED -> TicketStatus.RESERVED;
            case PENDING_CONFIRMATION -> TicketStatus.PENDING_CONFIRMATION;
            case SOLD -> TicketStatus.SOLD;
        };
    }
}
