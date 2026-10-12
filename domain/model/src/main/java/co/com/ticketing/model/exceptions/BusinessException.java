package co.com.ticketing.model.exceptions;

public abstract sealed class BusinessException extends RuntimeException
        permits DuplicateOrderException,
                EventNotFoundException,
                IdempotencyKeyReuseException,
                InsufficientTicketsException,
                InvalidDataException,
                InvalidTransitionException,
                OrderConflictException,
                OrderNotFoundException,
                PaymentDeclinedException,
                ReservationExpiredException {

    protected BusinessException(String message) {
        super(message);
    }
}
