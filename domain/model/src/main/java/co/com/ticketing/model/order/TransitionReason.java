package co.com.ticketing.model.order;

public enum TransitionReason {
    ORDER_PLACED,
    TICKETS_RESERVED,
    SOLD_OUT,
    PAYMENT_STARTED,
    PAYMENT_APPROVED,
    PAYMENT_DECLINED,
    RESERVATION_EXPIRED,
    PAYMENT_TIMEOUT,
    COMPLIMENTARY_ASSIGNED
}
