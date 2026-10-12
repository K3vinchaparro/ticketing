package co.com.ticketing.model.order;

import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTransitionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Order PENDING = Order.pending("o1", "e1", "c1", 3, NOW, NOW.plusSeconds(60));

    @Test
    void reservationMovesTicketsFromAvailableToReserved() {
        Instant later = NOW.plusSeconds(1);

        OrderTransition transition = OrderTransition.of(PENDING, OrderStatus.RESERVED, later,
                later.plusSeconds(600), TransitionReason.TICKETS_RESERVED);

        assertThat(transition.from()).isEqualTo(PENDING);
        assertThat(transition.to().status()).isEqualTo(OrderStatus.RESERVED);
        assertThat(transition.sourceTicketStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(transition.targetTicketStatus()).isEqualTo(TicketStatus.RESERVED);
        assertThat(transition.movesInventory()).isTrue();
        assertThat(transition.quantity()).isEqualTo(3);
        assertThat(transition.occurredAt()).isEqualTo(later);
        assertThat(transition.reason()).isEqualTo(TransitionReason.TICKETS_RESERVED);
    }

    @Test
    void rejectionDoesNotMoveInventoryBecauseTicketsWereNeverTaken() {
        OrderTransition transition = OrderTransition.of(PENDING, OrderStatus.REJECTED, NOW, null,
                TransitionReason.SOLD_OUT);

        assertThat(transition.movesInventory()).isFalse();
    }

    @Test
    void invalidTransitionCannotBeBuilt() {
        assertThatThrownBy(() -> OrderTransition.of(PENDING, OrderStatus.SOLD, NOW, null,
                TransitionReason.PAYMENT_APPROVED))
                .isInstanceOf(InvalidTransitionException.class);
    }
}
