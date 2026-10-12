package co.com.ticketing.model.order.gateways;

import co.com.ticketing.model.order.Order;
import reactor.core.publisher.Mono;

public interface OrderPublisher {

    Mono<Void> publish(Order order);
}
