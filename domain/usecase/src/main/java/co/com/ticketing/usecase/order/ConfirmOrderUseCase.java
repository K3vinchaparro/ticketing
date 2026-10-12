package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.exceptions.PaymentDeclinedException;
import co.com.ticketing.model.exceptions.ReservationExpiredException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPolicy;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.PaymentResult;
import co.com.ticketing.model.order.TransitionReason;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.PaymentGateway;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

@RequiredArgsConstructor
public class ConfirmOrderUseCase {

    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final OrderPolicy policy;
    private final Clock clock;

    public Mono<Order> confirm(String orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)))
                .flatMap(order -> switch (order.status()) {
                    case SOLD -> Mono.just(order);
                    case RESERVED -> startPayment(order);
                    default -> Mono.error(
                            new InvalidTransitionException(order.status(), OrderStatus.PENDING_CONFIRMATION));
                });
    }

    private Mono<Order> startPayment(Order reserved) {
        Instant now = clock.instant();
        if (reserved.isOverdue(now)) {
            return Mono.error(new ReservationExpiredException(reserved.orderId()));
        }
        OrderTransition paymentStarted = OrderTransition.of(reserved, OrderStatus.PENDING_CONFIRMATION, now,
                now.plus(policy.paymentTimeout()), TransitionReason.PAYMENT_STARTED);
        return orderRepository.apply(paymentStarted)
                .flatMap(inPayment -> paymentGateway.charge(inPayment)
                        .flatMap(result -> settle(inPayment, result)));
    }

    private Mono<Order> settle(Order inPayment, PaymentResult result) {
        Instant now = clock.instant();
        return switch (result) {
            case APPROVED -> orderRepository.apply(OrderTransition.of(inPayment, OrderStatus.SOLD, now, null,
                    TransitionReason.PAYMENT_APPROVED));
            case DECLINED -> orderRepository.apply(OrderTransition.of(inPayment, OrderStatus.RELEASED, now, null,
                            TransitionReason.PAYMENT_DECLINED))
                    .then(Mono.error(new PaymentDeclinedException(inPayment.orderId())));
        };
    }
}
