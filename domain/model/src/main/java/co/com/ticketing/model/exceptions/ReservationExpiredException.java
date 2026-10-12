package co.com.ticketing.model.exceptions;

public final class ReservationExpiredException extends BusinessException {

    public ReservationExpiredException(String orderId) {
        super("Reservation expired for order " + orderId);
    }
}
