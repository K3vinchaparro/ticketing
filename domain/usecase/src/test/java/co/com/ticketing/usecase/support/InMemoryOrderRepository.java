package co.com.ticketing.usecase.support;

import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.exceptions.DuplicateOrderException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.gateways.OrderRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private Inventory inventory;

    public InMemoryOrderRepository(int capacity) {
        this.inventory = Inventory.ofCapacity(capacity);
    }

    public Inventory inventory() {
        synchronized (lock) {
            return inventory;
        }
    }

    public List<Order> orders() {
        return List.copyOf(orders.values());
    }

    public void put(Order order) {
        orders.put(order.orderId(), order);
    }

    @Override
    public Mono<Order> create(Order order, String idempotencyKey) {
        return Mono.fromCallable(() -> {
            String scopedKey = order.customerId() + "#" + idempotencyKey;
            if (idempotencyKeys.putIfAbsent(scopedKey, order.orderId()) != null) {
                throw new DuplicateOrderException(idempotencyKey);
            }
            orders.put(order.orderId(), order);
            return order;
        });
    }

    @Override
    public Mono<Order> findById(String orderId) {
        return Mono.justOrEmpty(orders.get(orderId));
    }

    @Override
    public Mono<Order> findByIdempotencyKey(String customerId, String idempotencyKey) {
        return Mono.justOrEmpty(idempotencyKeys.get(customerId + "#" + idempotencyKey)).map(orders::get);
    }

    @Override
    public Flux<Order> findOverdue(OrderStatus status, Instant now) {
        return Flux.fromIterable(orders())
                .filter(order -> order.status() == status && order.isOverdue(now));
    }

    @Override
    public Mono<Order> apply(OrderTransition transition) {
        return Mono.fromCallable(() -> {
            Order to = transition.to();
            synchronized (lock) {
                if (orders.get(to.orderId()).status() != transition.from().status()) {
                    throw new OrderConflictException(to.orderId());
                }
                if (transition.movesInventory()) {
                    inventory = inventory.move(transition.sourceTicketStatus(), transition.targetTicketStatus(),
                            transition.quantity());
                }
                orders.put(to.orderId(), to);
            }
            return to;
        });
    }

    @Override
    public Mono<Order> postpone(Order order, Instant newDeadline) {
        return Mono.fromCallable(() -> {
            Order postponed = order.withDeadline(newDeadline);
            orders.put(order.orderId(), postponed);
            return postponed;
        });
    }
}
