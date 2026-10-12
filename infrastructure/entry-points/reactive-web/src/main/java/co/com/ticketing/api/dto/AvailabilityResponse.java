package co.com.ticketing.api.dto;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;

public record AvailabilityResponse(String eventId, int capacity, int available, int reserved, int pendingConfirmation,
                                   int sold, int complimentary) {

    public static AvailabilityResponse from(Event event) {
        Inventory inventory = event.inventory();
        return new AvailabilityResponse(event.eventId(), event.capacity(), inventory.available(),
                inventory.reserved(), inventory.pendingConfirmation(), inventory.sold(), inventory.complimentary());
    }
}
