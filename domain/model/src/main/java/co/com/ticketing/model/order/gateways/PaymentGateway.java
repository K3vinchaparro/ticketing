package co.com.ticketing.model.order.gateways;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.PaymentResult;
import reactor.core.publisher.Mono;

public interface PaymentGateway {

    Mono<PaymentResult> charge(Order order);
}
