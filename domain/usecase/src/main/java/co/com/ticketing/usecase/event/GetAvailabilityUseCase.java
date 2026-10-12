package co.com.ticketing.usecase.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class GetAvailabilityUseCase {

    private final EventRepository eventRepository;
    private final Map<String, Flux<Event>> streams = new ConcurrentHashMap<>();

    public Mono<Event> current(String eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)));
    }

    public Flux<Event> stream(String eventId, Duration pollInterval) {
        return streams.computeIfAbsent(eventId, id -> poll(id, pollInterval)
                .doFinally(_ -> streams.remove(id))
                .replay(1)
                .refCount());
    }

    private Flux<Event> poll(String eventId, Duration pollInterval) {
        return Flux.interval(Duration.ZERO, pollInterval)
                .onBackpressureDrop()
                .concatMap(_ -> current(eventId))
                .distinctUntilChanged(Event::inventory);
    }
}
