package co.com.ticketing.scheduler;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.usecase.order.ReleaseExpiredReservationsUseCase;
import co.com.ticketing.usecase.order.RepublishPendingOrdersUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

    private final ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;
    private final RepublishPendingOrdersUseCase republishPendingOrdersUseCase;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean releasing = new AtomicBoolean();
    private final AtomicBoolean republishing = new AtomicBoolean();

    @Scheduled(fixedDelayString = "${scheduler.release-interval:PT30S}")
    public void releaseExpiredReservations() {
        run("reservations.released", releasing, releaseExpiredReservationsUseCase.execute());
    }

    @Scheduled(fixedDelayString = "${scheduler.republish-interval:PT30S}")
    public void republishPendingOrders() {
        run("orders.republished", republishing, republishPendingOrdersUseCase.execute());
    }

    private void run(String metric, AtomicBoolean busy, Flux<Order> job) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        job.doOnNext(order -> {
                    meterRegistry.counter(metric).increment();
                    log.info("{}: order {} is now {}", metric, order.orderId(), order.status());
                })
                .doOnError(error -> log.error("Job {} failed; it will run again on the next tick", metric, error))
                .onErrorResume(_ -> Flux.empty())
                .doFinally(_ -> busy.set(false))
                .subscribe();
    }
}
