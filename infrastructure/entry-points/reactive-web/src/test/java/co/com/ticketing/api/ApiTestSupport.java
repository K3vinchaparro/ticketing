package co.com.ticketing.api;

import co.com.ticketing.api.config.ApiProperties;
import co.com.ticketing.api.validation.RequestValidator;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import jakarta.validation.Validation;

import java.time.Duration;
import java.time.Instant;

public final class ApiTestSupport {

    public static final String ADMIN_KEY = "test-admin-key";
    public static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private ApiTestSupport() {
    }

    public static ApiProperties properties(boolean rateLimitEnabled, int requests) {
        return new ApiProperties(ADMIN_KEY, Duration.ofMillis(50),
                new ApiProperties.RateLimit(rateLimitEnabled, requests, Duration.ofSeconds(10)));
    }

    public static RequestValidator validator() {
        return new RequestValidator(Validation.buildDefaultValidatorFactory());
    }

    public static Event event(Inventory inventory) {
        return new Event("e1", "Rock Fest", Instant.parse("2027-01-01T20:00:00Z"), "Arena", inventory, NOW);
    }

    public static Order order(OrderStatus status) {
        Instant deadline = status.isFinal() ? null : NOW.plusSeconds(600);
        return new Order("o1", "e1", "c1", 2, status, deadline, NOW, NOW);
    }
}
