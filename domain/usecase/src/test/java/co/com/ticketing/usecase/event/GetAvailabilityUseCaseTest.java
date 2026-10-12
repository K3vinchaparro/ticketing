package co.com.ticketing.usecase.event;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static co.com.ticketing.usecase.support.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAvailabilityUseCaseTest {

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private GetAvailabilityUseCase useCase;

    @Test
    void returnsTheCurrentCounters() {
        Event event = event("e1", 10);
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event));

        StepVerifier.create(useCase.current("e1")).expectNext(event).verifyComplete();
    }

    @Test
    void failsWhenTheEventDoesNotExist() {
        when(eventRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.current("missing")).verifyError(EventNotFoundException.class);
    }

    @Test
    void streamEmitsOnlyWhenTheAvailabilityChanges() {
        Event full = event("e1", 10);
        Event oneReserved = new Event(full.eventId(), full.name(), full.date(), full.venue(),
                new Inventory(9, 1, 0, 0, 0), full.createdAt());
        when(eventRepository.findById("e1"))
                .thenReturn(Mono.just(full), Mono.just(full), Mono.just(oneReserved));

        StepVerifier.withVirtualTime(() -> useCase.stream("e1", Duration.ofSeconds(1)).take(2))
                .expectSubscription()
                .assertNext(event -> assertThat(event.inventory().available()).isEqualTo(10))
                .thenAwait(Duration.ofSeconds(1))
                .expectNoEvent(Duration.ofMillis(500))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(event -> assertThat(event.inventory().available()).isEqualTo(9))
                .verifyComplete();
    }

    @Test
    void viewersOfTheSameEventShareOnePoll() {
        Event full = event("e1", 10);
        AtomicInteger reads = new AtomicInteger();
        when(eventRepository.findById("e1")).thenReturn(Mono.fromSupplier(() -> {
            reads.incrementAndGet();
            return full;
        }));

        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            List<Event> first = new ArrayList<>();
            List<Event> second = new ArrayList<>();
            Disposable firstViewer = useCase.stream("e1", Duration.ofSeconds(1)).subscribe(first::add);
            Disposable secondViewer = useCase.stream("e1", Duration.ofSeconds(1)).subscribe(second::add);
            scheduler.advanceTimeBy(Duration.ofSeconds(3));

            assertThat(first).containsExactly(full);
            assertThat(second).containsExactly(full);
            assertThat(reads).hasValue(4);

            firstViewer.dispose();
            secondViewer.dispose();
            scheduler.advanceTimeBy(Duration.ofSeconds(3));
            assertThat(reads).hasValue(4);
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void pollingStartsAgainForANewViewerAfterEveryoneLeft() {
        Event full = event("e1", 10);
        when(eventRepository.findById("e1")).thenReturn(Mono.just(full));

        StepVerifier.create(useCase.stream("e1", Duration.ofMillis(10)).take(1)).expectNext(full).verifyComplete();
        StepVerifier.create(useCase.stream("e1", Duration.ofMillis(10)).take(1)).expectNext(full).verifyComplete();

        verify(eventRepository, times(2)).findById("e1");
    }
}
