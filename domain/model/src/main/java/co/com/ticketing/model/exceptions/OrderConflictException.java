package co.com.ticketing.model.exceptions;

public final class OrderConflictException extends BusinessException {

    public OrderConflictException(String orderId) {
        super("Order " + orderId + " was modified by another process");
    }
}
