package co.com.ticketing.model.event;

import co.com.ticketing.model.exceptions.InvalidDataException;

import java.time.Instant;

public record Event(String eventId, String name, Instant date, String venue, Inventory inventory, Instant createdAt) {

    public static final int MAX_CAPACITY = 1_000_000;
    private static final int MAX_TEXT_LENGTH = 200;

    public Event {
        requireText(eventId, "eventId");
        requireText(name, "name");
        requireText(venue, "venue");
        requireNonNull(date, "date");
        requireNonNull(inventory, "inventory");
        requireNonNull(createdAt, "createdAt");
    }

    public static Event create(String eventId, String name, Instant date, String venue, int capacity, Instant now) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new InvalidDataException("capacity must be between 1 and " + MAX_CAPACITY);
        }
        if (date == null || !date.isAfter(now)) {
            throw new InvalidDataException("date must be in the future");
        }
        return new Event(eventId, name, date, venue, Inventory.ofCapacity(capacity), now);
    }

    public int capacity() {
        return inventory.capacity();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new InvalidDataException(field + " is required and must have at most " + MAX_TEXT_LENGTH + " characters");
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new InvalidDataException(field + " is required");
        }
    }
}
