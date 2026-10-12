package co.com.ticketing.usecase.order;

import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.DuplicateOrderException;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import co.com.ticketing.model.exceptions.IdempotencyKeyReuseException;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPolicy;
import co.com.ticketing.model.order.PlaceOrderCommand;
import co.com.ticketing.model.order.PlacedOrder;
import co.com.ticketing.model.order.gateways.OrderPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
public class PlaceOrderUseCase {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final OrderPublisher orderPublisher;
    private final OrderPolicy policy;
    private final Clock clock;

    public Mono<PlacedOrder> place(PlaceOrderCommand command) {
        if (command.quantity() < 1 || command.quantity() > policy.maxTicketsPerOrder()) {
            return Mono.error(new InvalidDataException(
                    "quantity must be between 1 and " + policy.maxTicketsPerOrder()));
        }
        return findExisting(command)
                .switchIfEmpty(Mono.defer(() -> createNew(command)));
    }

    private Mono<PlacedOrder> findExisting(PlaceOrderCommand command) {
        return orderRepository.findByIdempotencyKey(command.customerId(), command.idempotencyKey())
                .flatMap(existing -> command.matches(existing)
                        ? Mono.just(new PlacedOrder(existing, true))
                        : Mono.error(new IdempotencyKeyReuseException(command.idempotencyKey())));
    }

    private Mono<PlacedOrder> createNew(PlaceOrderCommand command) {
        return eventRepository.findById(command.eventId())
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(command.eventId())))
                .filter(event -> event.inventory().available() >= command.quantity())
                .switchIfEmpty(Mono.error(() -> new InsufficientTicketsException(command.quantity())))
                .flatMap(_ -> orderRepository.create(newOrder(command), command.idempotencyKey()))
                .flatMap(saved -> orderPublisher.publish(saved).thenReturn(new PlacedOrder(saved, false)))
                .onErrorResume(DuplicateOrderException.class, _ -> findExisting(command));
    }

    private Order newOrder(PlaceOrderCommand command) {
        Instant now = clock.instant();
        return Order.pending(UUID.randomUUID().toString(), command.eventId(), command.customerId(),
                command.quantity(), now, now.plus(policy.processingTimeout()));
    }
}
