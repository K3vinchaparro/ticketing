package co.com.ticketing.model.order.gateways;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface OrderRepository {

    Mono<Order> create(Order order, String idempotencyKey);

    Mono<Order> findById(String orderId);

    Mono<Order> findByIdempotencyKey(String customerId, String idempotencyKey);

    Flux<Order> findOverdue(OrderStatus status, Instant now);

    Mono<Order> apply(OrderTransition transition);

    Mono<Order> postpone(Order order, Instant newDeadline);
}
