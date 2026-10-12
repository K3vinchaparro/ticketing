package co.com.ticketing.api.dto;

import co.com.ticketing.model.event.Event;

import java.time.Instant;

public record EventResponse(String eventId, String name, Instant date, String venue, int capacity, int available,
                            Instant createdAt) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.eventId(), event.name(), event.date(), event.venue(), event.capacity(),
                event.inventory().available(), event.createdAt());
    }
}
