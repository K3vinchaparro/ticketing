package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.gateways.OrderRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class GetOrderUseCase {

    private final OrderRepository orderRepository;

    public Mono<Order> getById(String orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }
}
