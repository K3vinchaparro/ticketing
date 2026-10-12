package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPolicy;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.gateways.OrderPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

@RequiredArgsConstructor
public class RepublishPendingOrdersUseCase {

    private static final int CONCURRENCY = 8;

    private final OrderRepository orderRepository;
    private final OrderPublisher orderPublisher;
    private final OrderPolicy policy;
    private final Clock clock;

    public Flux<Order> execute() {
        Instant now = clock.instant();
        Instant nextDeadline = now.plus(policy.processingTimeout());
        return orderRepository.findOverdue(OrderStatus.PENDING, now)
                .flatMap(order -> orderPublisher.publish(order)
                        .then(Mono.defer(() -> orderRepository.postpone(order, nextDeadline)))
                        .onErrorResume(OrderConflictException.class, _ -> Mono.empty()), CONCURRENCY);
    }
}
