package co.com.ticketing.usecase.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.InvalidDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static co.com.ticketing.usecase.support.Fixtures.CLOCK;
import static co.com.ticketing.usecase.support.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateEventUseCaseTest {

    private static final Instant DATE = NOW.plus(Duration.ofDays(10));

    @Mock
    private EventRepository eventRepository;

    private CreateEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateEventUseCase(eventRepository, CLOCK);
    }

    @Test
    void storesANewEventWithItsWholeCapacityAvailable() {
        when(eventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.<Event>getArgument(0)));

        StepVerifier.create(useCase.create("Rock Fest", DATE, "Arena", 500))
                .assertNext(event -> {
                    assertThat(event.eventId()).isNotBlank();
                    assertThat(event.name()).isEqualTo("Rock Fest");
                    assertThat(event.inventory()).isEqualTo(Inventory.ofCapacity(500));
                    assertThat(event.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void rejectsInvalidDataWithoutTouchingTheRepository() {
        StepVerifier.create(useCase.create("Rock Fest", DATE, "Arena", 0))
                .verifyError(InvalidDataException.class);

        verifyNoInteractions(eventRepository);
    }
}
