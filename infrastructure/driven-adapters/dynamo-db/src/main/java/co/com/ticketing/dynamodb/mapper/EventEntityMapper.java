package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.dynamodb.entity.EventEntity;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;

public final class EventEntityMapper {

    private EventEntityMapper() {
    }

    public static EventEntity toEntity(Event event) {
        Inventory inventory = event.inventory();
        return EventEntity.builder()
                .eventId(event.eventId())
                .name(event.name())
                .date(event.date())
                .venue(event.venue())
                .capacity(event.capacity())
                .available(inventory.available())
                .reserved(inventory.reserved())
                .pendingConfirmation(inventory.pendingConfirmation())
                .sold(inventory.sold())
                .complimentary(inventory.complimentary())
                .version(0L)
                .createdAt(event.createdAt())
                .build();
    }

    public static Event toDomain(EventEntity entity) {
        Inventory inventory = new Inventory(entity.getAvailable(), entity.getReserved(),
                entity.getPendingConfirmation(), entity.getSold(), entity.getComplimentary());
        return new Event(entity.getEventId(), entity.getName(), entity.getDate(), entity.getVenue(), inventory,
                entity.getCreatedAt());
    }
}
