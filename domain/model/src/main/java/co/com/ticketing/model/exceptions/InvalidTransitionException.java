package co.com.ticketing.model.exceptions;

public final class InvalidTransitionException extends BusinessException {

    public InvalidTransitionException(Enum<?> from, Enum<?> to) {
        super("Transition not allowed: " + from + " -> " + to);
    }
}
