package co.com.ticketing.model.exceptions;

public final class InsufficientTicketsException extends BusinessException {

    public InsufficientTicketsException(int requested) {
        super("Not enough tickets to fulfil a request of " + requested);
    }
}
