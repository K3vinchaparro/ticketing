package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.TransitionReason;
import co.com.ticketing.model.order.gateways.OrderRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

@RequiredArgsConstructor
public class ReleaseExpiredReservationsUseCase {

    private static final int CONCURRENCY = 8;

    private final OrderRepository orderRepository;
    private final Clock clock;

    public Flux<Order> execute() {
        Instant now = clock.instant();
        return Flux.concat(
                release(OrderStatus.RESERVED, TransitionReason.RESERVATION_EXPIRED, now),
                release(OrderStatus.PENDING_CONFIRMATION, TransitionReason.PAYMENT_TIMEOUT, now));
    }

    private Flux<Order> release(OrderStatus status, TransitionReason reason, Instant now) {
        return orderRepository.findOverdue(status, now)
                .flatMap(order -> orderRepository
                        .apply(OrderTransition.of(order, OrderStatus.RELEASED, now, null, reason))
                        .onErrorResume(OrderConflictException.class, _ -> Mono.empty()), CONCURRENCY);
    }
}
