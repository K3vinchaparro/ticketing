package co.com.ticketing.model.exceptions;

public final class IdempotencyKeyReuseException extends BusinessException {

    public IdempotencyKeyReuseException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey + " was already used with a different request");
    }
}
