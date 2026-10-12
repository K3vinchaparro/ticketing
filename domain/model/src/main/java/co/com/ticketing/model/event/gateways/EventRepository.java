package co.com.ticketing.model.event.gateways;

import co.com.ticketing.model.event.Event;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface EventRepository {

    Mono<Event> save(Event event);

    Mono<Event> findById(String eventId);

    Flux<Event> findAll();

    Mono<Event> assignComplimentary(String eventId, int quantity, Instant occurredAt);
}
