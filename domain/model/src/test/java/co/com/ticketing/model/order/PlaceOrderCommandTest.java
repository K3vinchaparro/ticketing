package co.com.ticketing.model.order;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceOrderCommandTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Order ORDER = Order.pending("o1", "e1", "c1", 2, NOW, NOW.plusSeconds(60));

    @Test
    void matchesAnOrderCreatedFromTheSameRequest() {
        assertThat(new PlaceOrderCommand("e1", "c1", 2, "key").matches(ORDER)).isTrue();
    }

    @Test
    void doesNotMatchWhenAnyFieldDiffers() {
        assertThat(new PlaceOrderCommand("e2", "c1", 2, "key").matches(ORDER)).isFalse();
        assertThat(new PlaceOrderCommand("e1", "c2", 2, "key").matches(ORDER)).isFalse();
        assertThat(new PlaceOrderCommand("e1", "c1", 3, "key").matches(ORDER)).isFalse();
    }
}
