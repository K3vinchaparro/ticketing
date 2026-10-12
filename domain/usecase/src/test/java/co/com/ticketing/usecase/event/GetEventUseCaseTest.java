package co.com.ticketing.usecase.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static co.com.ticketing.usecase.support.Fixtures.event;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetEventUseCaseTest {

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private GetEventUseCase useCase;

    @Test
    void returnsTheEvent() {
        Event event = event("e1", 10);
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event));

        StepVerifier.create(useCase.getById("e1")).expectNext(event).verifyComplete();
    }

    @Test
    void failsWhenTheEventDoesNotExist() {
        when(eventRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.getById("missing")).verifyError(EventNotFoundException.class);
    }

    @Test
    void listsEveryEvent() {
        when(eventRepository.findAll()).thenReturn(Flux.just(event("e1", 10), event("e2", 20)));

        StepVerifier.create(useCase.getAll()).expectNextCount(2).verifyComplete();
    }
}
