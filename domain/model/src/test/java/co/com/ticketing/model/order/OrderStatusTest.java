package co.com.ticketing.model.order;

import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;

import static co.com.ticketing.model.order.OrderStatus.PENDING;
import static co.com.ticketing.model.order.OrderStatus.PENDING_CONFIRMATION;
import static co.com.ticketing.model.order.OrderStatus.REJECTED;
import static co.com.ticketing.model.order.OrderStatus.RELEASED;
import static co.com.ticketing.model.order.OrderStatus.RESERVED;
import static co.com.ticketing.model.order.OrderStatus.SOLD;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING, Set.of(RESERVED, REJECTED),
            RESERVED, Set.of(PENDING_CONFIRMATION, RELEASED),
            PENDING_CONFIRMATION, Set.of(SOLD, RELEASED),
            SOLD, Set.of(),
            REJECTED, Set.of(),
            RELEASED, Set.of());

    @Test
    void stateMachineAllowsOnlyTheDefinedTransitions() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SOLD", "REJECTED", "RELEASED"})
    void finalStatesCannotBeLeft(OrderStatus status) {
        assertThat(status.isFinal()).isTrue();
        assertThat(OrderStatus.values()).noneMatch(status::canTransitionTo);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "RESERVED", "PENDING_CONFIRMATION"})
    void activeStatesAreNotFinal(OrderStatus status) {
        assertThat(status.isFinal()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,AVAILABLE", "REJECTED,AVAILABLE", "RELEASED,AVAILABLE",
            "RESERVED,RESERVED", "PENDING_CONFIRMATION,PENDING_CONFIRMATION", "SOLD,SOLD"})
    void eachOrderStatusMapsToTheStateOfItsTickets(OrderStatus orderStatus, TicketStatus ticketStatus) {
        assertThat(orderStatus.ticketStatus()).isEqualTo(ticketStatus);
    }
}
