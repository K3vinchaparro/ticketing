package co.com.ticketing.model.exceptions;

public final class DuplicateOrderException extends BusinessException {

    public DuplicateOrderException(String idempotencyKey) {
        super("An order already exists for idempotency key " + idempotencyKey);
    }
}
