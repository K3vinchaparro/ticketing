package co.com.ticketing.model.event;

import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    @Test
    void newInventoryHasEveryTicketAvailable() {
        Inventory inventory = Inventory.ofCapacity(100);

        assertThat(inventory).isEqualTo(new Inventory(100, 0, 0, 0, 0));
        assertThat(inventory.capacity()).isEqualTo(100);
    }

    @Test
    void movingTicketsKeepsTheCapacityInvariant() {
        Inventory inventory = Inventory.ofCapacity(10)
                .move(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 4)
                .move(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, 3)
                .move(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD, 2)
                .move(TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, 1)
                .move(TicketStatus.RESERVED, TicketStatus.AVAILABLE, 1);

        assertThat(inventory).isEqualTo(new Inventory(6, 0, 1, 2, 1));
        assertThat(inventory.capacity()).isEqualTo(10);
    }

    @ParameterizedTest
    @CsvSource({"AVAILABLE,5", "RESERVED,4", "PENDING_CONFIRMATION,3", "SOLD,2", "COMPLIMENTARY,1"})
    void countReturnsTheCounterOfEachStatus(TicketStatus status, int expected) {
        assertThat(new Inventory(5, 4, 3, 2, 1).count(status)).isEqualTo(expected);
    }

    @Test
    void cannotMoveMoreTicketsThanTheSourceHas() {
        Inventory inventory = Inventory.ofCapacity(2);

        assertThatThrownBy(() -> inventory.move(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 3))
                .isInstanceOf(InsufficientTicketsException.class)
                .hasMessageContaining("3");
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"SOLD", "COMPLIMENTARY"})
    void ticketsNeverLeaveAFinalState(TicketStatus finalStatus) {
        Inventory inventory = new Inventory(0, 0, 0, 5, 5);

        assertThatThrownBy(() -> inventory.move(finalStatus, TicketStatus.AVAILABLE, 1))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void cannotMoveTicketsToTheSameState() {
        Inventory inventory = Inventory.ofCapacity(5);

        assertThatThrownBy(() -> inventory.move(TicketStatus.AVAILABLE, TicketStatus.AVAILABLE, 1))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void quantityToMoveMustBePositive() {
        Inventory inventory = Inventory.ofCapacity(5);

        assertThatThrownBy(() -> inventory.move(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 0))
                .isInstanceOf(InvalidDataException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1,0,0,0,0", "0,-1,0,0,0", "0,0,-1,0,0", "0,0,0,-1,0", "0,0,0,0,-1"})
    void countersCannotBeNegative(int available, int reserved, int pending, int sold, int complimentary) {
        assertThatThrownBy(() -> new Inventory(available, reserved, pending, sold, complimentary))
                .isInstanceOf(InvalidDataException.class);
    }
}
