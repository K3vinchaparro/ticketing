package co.com.ticketing.usecase.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import co.com.ticketing.model.exceptions.InvalidDataException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;

@RequiredArgsConstructor
public class AssignComplimentaryUseCase {

    private final EventRepository eventRepository;
    private final Clock clock;

    public Mono<Event> assign(String eventId, int quantity) {
        if (quantity < 1) {
            return Mono.error(new InvalidDataException("quantity must be at least 1"));
        }
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)))
                .flatMap(_ -> eventRepository.assignComplimentary(eventId, quantity, clock.instant()));
    }
}
