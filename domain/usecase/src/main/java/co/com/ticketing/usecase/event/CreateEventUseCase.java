package co.com.ticketing.usecase.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
public class CreateEventUseCase {

    private final EventRepository eventRepository;
    private final Clock clock;

    public Mono<Event> create(String name, Instant date, String venue, int capacity) {
        return Mono.fromSupplier(() ->
                        Event.create(UUID.randomUUID().toString(), name, date, venue, capacity, clock.instant()))
                .flatMap(eventRepository::save);
    }
}
