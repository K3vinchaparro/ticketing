package co.com.ticketing.model.exceptions;

public final class PaymentDeclinedException extends BusinessException {

    public PaymentDeclinedException(String orderId) {
        super("Payment declined for order " + orderId);
    }
}
