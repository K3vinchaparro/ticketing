package co.com.ticketing.model.ticket;

public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    public boolean isFinal() {
        return this == SOLD || this == COMPLIMENTARY;
    }

    public boolean isAccountable() {
        return this == SOLD;
    }
}
