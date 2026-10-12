package co.com.ticketing.model.exceptions;

public final class EventNotFoundException extends BusinessException {

    public EventNotFoundException(String eventId) {
        super("Event not found: " + eventId);
    }
}
