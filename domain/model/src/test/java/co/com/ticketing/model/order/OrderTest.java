package co.com.ticketing.model.order;

import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(600);

    private static Order pending() {
        return Order.pending("o1", "e1", "c1", 2, NOW, DEADLINE);
    }

    @Test
    void newOrderStartsPendingWithItsProcessingDeadline() {
        Order order = pending();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.expiresAt()).isEqualTo(DEADLINE);
        assertThat(order.createdAt()).isEqualTo(NOW);
        assertThat(order.updatedAt()).isEqualTo(NOW);
        assertThat(order.ticketStatus()).isEqualTo(TicketStatus.AVAILABLE);
    }

    @Test
    void transitionReturnsACopyAndLeavesTheOriginalUntouched() {
        Order order = pending();
        Instant later = NOW.plusSeconds(5);

        Order reserved = order.transitionTo(OrderStatus.RESERVED, later, later.plusSeconds(600));

        assertThat(reserved.status()).isEqualTo(OrderStatus.RESERVED);
        assertThat(reserved.updatedAt()).isEqualTo(later);
        assertThat(reserved.expiresAt()).isEqualTo(later.plusSeconds(600));
        assertThat(reserved.createdAt()).isEqualTo(NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void finalStatesHaveNoDeadline() {
        Order rejected = pending().transitionTo(OrderStatus.REJECTED, NOW, DEADLINE);

        assertThat(rejected.expiresAt()).isNull();
    }

    @Test
    void transitionOutsideTheStateMachineIsRejected() {
        Order order = pending();

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.SOLD, NOW, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("PENDING -> SOLD");
    }

    @Test
    void orderIsOverdueFromItsDeadlineOnwards() {
        Order order = pending();

        assertThat(order.isOverdue(DEADLINE.minusSeconds(1))).isFalse();
        assertThat(order.isOverdue(DEADLINE)).isTrue();
        assertThat(order.isOverdue(DEADLINE.plusSeconds(1))).isTrue();
    }

    @Test
    void orderWithoutDeadlineIsNeverOverdue() {
        Order sold = pending().withDeadline(null);

        assertThat(sold.isOverdue(Instant.MAX)).isFalse();
    }

    @Test
    void withDeadlineOnlyChangesTheDeadline() {
        Order order = pending();
        Instant newDeadline = DEADLINE.plusSeconds(60);

        assertThat(order.withDeadline(newDeadline))
                .isEqualTo(new Order("o1", "e1", "c1", 2, OrderStatus.PENDING, newDeadline, NOW, NOW));
    }

    @Test
    void requiredFieldsAreValidated() {
        assertThatThrownBy(() -> Order.pending(" ", "e1", "c1", 1, NOW, DEADLINE))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("orderId");
        assertThatThrownBy(() -> Order.pending("o1", null, "c1", 1, NOW, DEADLINE))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> Order.pending("o1", "e1", "", 1, NOW, DEADLINE))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("customerId");
        assertThatThrownBy(() -> Order.pending("o1", "e1", "c1", 0, NOW, DEADLINE))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("quantity");
        assertThatThrownBy(() -> new Order("o1", "e1", "c1", 1, null, null, NOW, NOW))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> new Order("o1", "e1", "c1", 1, OrderStatus.PENDING, null, null, NOW))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> new Order("o1", "e1", "c1", 1, OrderStatus.PENDING, null, NOW, null))
                .isInstanceOf(InvalidDataException.class);
    }
}
