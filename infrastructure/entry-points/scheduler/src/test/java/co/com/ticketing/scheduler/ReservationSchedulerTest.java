package co.com.ticketing.scheduler;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.usecase.order.ReleaseExpiredReservationsUseCase;
import co.com.ticketing.usecase.order.RepublishPendingOrdersUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    private ReleaseExpiredReservationsUseCase releaseUseCase;
    @Mock
    private RepublishPendingOrdersUseCase republishUseCase;

    private SimpleMeterRegistry meterRegistry;
    private ReservationScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new ReservationScheduler(releaseUseCase, republishUseCase, meterRegistry);
    }

    private static Order order(String orderId, OrderStatus status) {
        return new Order(orderId, "e1", "c1", 1, status, null, NOW, NOW);
    }

    @Test
    void countsEveryReleasedReservation() {
        when(releaseUseCase.execute())
                .thenReturn(Flux.just(order("o1", OrderStatus.RELEASED), order("o2", OrderStatus.RELEASED)));

        scheduler.releaseExpiredReservations();

        assertThat(meterRegistry.counter("reservations.released").count()).isEqualTo(2);
    }

    @Test
    void countsEveryRepublishedOrder() {
        when(republishUseCase.execute()).thenReturn(Flux.just(order("o1", OrderStatus.PENDING)));

        scheduler.republishPendingOrders();

        assertThat(meterRegistry.counter("orders.republished").count()).isEqualTo(1);
    }

    @Test
    void failureIsContainedAndTheNextRunWorks() {
        when(releaseUseCase.execute())
                .thenReturn(Flux.error(new IllegalStateException("database down")))
                .thenReturn(Flux.just(order("o1", OrderStatus.RELEASED)));

        scheduler.releaseExpiredReservations();
        scheduler.releaseExpiredReservations();

        verify(releaseUseCase, times(2)).execute();
        assertThat(meterRegistry.counter("reservations.released").count()).isEqualTo(1);
    }

    @Test
    void aRunStillInProgressIsNotOverlappedByTheNextTick() {
        Sinks.Many<Order> slowJob = Sinks.many().unicast().onBackpressureBuffer();
        when(releaseUseCase.execute()).thenReturn(slowJob.asFlux());

        scheduler.releaseExpiredReservations();
        scheduler.releaseExpiredReservations();
        slowJob.tryEmitComplete();
        scheduler.releaseExpiredReservations();

        verify(releaseUseCase, times(3)).execute();
        assertThat(slowJob.currentSubscriberCount()).isZero();
    }
}
