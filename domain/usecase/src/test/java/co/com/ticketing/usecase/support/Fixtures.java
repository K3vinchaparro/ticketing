package co.com.ticketing.usecase.support;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderPolicy;
import co.com.ticketing.model.order.OrderStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final OrderPolicy POLICY =
            new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(3), 6);

    private Fixtures() {
    }

    public static Event event(String eventId, int capacity) {
        return Event.create(eventId, "Rock Fest", NOW.plus(Duration.ofDays(30)), "Arena", capacity, NOW);
    }

    public static Order order(String orderId, OrderStatus status, int quantity) {
        Instant deadline = status.isFinal() ? null : NOW.plus(Duration.ofMinutes(5));
        return new Order(orderId, "e1", "c1", quantity, status, deadline, NOW, NOW);
    }

    public static Order order(OrderStatus status) {
        return order("o1", status, 2);
    }
}
