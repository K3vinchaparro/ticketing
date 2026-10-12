package co.com.ticketing.model.exceptions;

public final class OrderNotFoundException extends BusinessException {

    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
    }
}
