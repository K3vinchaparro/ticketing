package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPolicy;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.TransitionReason;
import co.com.ticketing.model.order.gateways.OrderRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

@RequiredArgsConstructor
public class ProcessOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderPolicy policy;
    private final Clock clock;

    public Mono<Order> process(String orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)))
                .flatMap(order -> order.status() == OrderStatus.PENDING ? reserve(order) : Mono.just(order));
    }

    private Mono<Order> reserve(Order order) {
        Instant now = clock.instant();
        OrderTransition reservation = OrderTransition.of(order, OrderStatus.RESERVED, now,
                now.plus(policy.reservationTtl()), TransitionReason.TICKETS_RESERVED);
        return orderRepository.apply(reservation)
                .onErrorResume(InsufficientTicketsException.class, _ -> reject(order, now))
                .onErrorResume(OrderConflictException.class, _ -> currentState(order));
    }

    private Mono<Order> reject(Order order, Instant now) {
        OrderTransition rejection = OrderTransition.of(order, OrderStatus.REJECTED, now, null,
                TransitionReason.SOLD_OUT);
        return orderRepository.apply(rejection);
    }

    private Mono<Order> currentState(Order order) {
        return orderRepository.findById(order.orderId());
    }
}
