package co.com.ticketing.model.ticket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TicketStatusTest {

    @Test
    void onlySoldAndComplimentaryAreFinal() {
        assertThat(TicketStatus.values())
                .filteredOn(TicketStatus::isFinal)
                .containsExactly(TicketStatus.SOLD, TicketStatus.COMPLIMENTARY);
    }

    @Test
    void onlySoldTicketsAreAccountable() {
        assertThat(TicketStatus.values())
                .filteredOn(TicketStatus::isAccountable)
                .containsExactly(TicketStatus.SOLD);
    }
}
