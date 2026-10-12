package co.com.ticketing.model.order;

import co.com.ticketing.model.ticket.TicketStatus;

import java.time.Instant;

public record OrderTransition(Order from, Order to, TransitionReason reason) {

    public static OrderTransition of(Order current, OrderStatus next, Instant now, Instant deadline,
                                     TransitionReason reason) {
        return new OrderTransition(current, current.transitionTo(next, now, deadline), reason);
    }

    public TicketStatus sourceTicketStatus() {
        return from.ticketStatus();
    }

    public TicketStatus targetTicketStatus() {
        return to.ticketStatus();
    }

    public boolean movesInventory() {
        return sourceTicketStatus() != targetTicketStatus();
    }

    public int quantity() {
        return to.quantity();
    }

    public Instant occurredAt() {
        return to.updatedAt();
    }
}
