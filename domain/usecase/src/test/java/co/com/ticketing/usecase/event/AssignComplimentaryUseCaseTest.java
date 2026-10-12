package co.com.ticketing.usecase.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.InvalidDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static co.com.ticketing.usecase.support.Fixtures.CLOCK;
import static co.com.ticketing.usecase.support.Fixtures.NOW;
import static co.com.ticketing.usecase.support.Fixtures.event;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignComplimentaryUseCaseTest {

    @Mock
    private EventRepository eventRepository;

    private AssignComplimentaryUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new AssignComplimentaryUseCase(eventRepository, CLOCK);
    }

    @Test
    void movesTicketsToComplimentary() {
        Event event = event("e1", 10);
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event));
        when(eventRepository.assignComplimentary("e1", 3, NOW)).thenReturn(Mono.just(event));

        StepVerifier.create(useCase.assign("e1", 3)).expectNext(event).verifyComplete();
    }

    @Test
    void failsWhenTheEventDoesNotExist() {
        when(eventRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.assign("missing", 1)).verifyError(EventNotFoundException.class);

        verify(eventRepository, never()).assignComplimentary(any(), anyInt(), any());
    }

    @Test
    void failsWhenThereAreNotEnoughAvailableTickets() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event("e1", 2)));
        when(eventRepository.assignComplimentary("e1", 5, NOW))
                .thenReturn(Mono.error(new InsufficientTicketsException(5)));

        StepVerifier.create(useCase.assign("e1", 5)).verifyError(InsufficientTicketsException.class);
    }

    @Test
    void quantityMustBePositive() {
        StepVerifier.create(useCase.assign("e1", 0)).verifyError(InvalidDataException.class);

        verifyNoInteractions(eventRepository);
    }
}
