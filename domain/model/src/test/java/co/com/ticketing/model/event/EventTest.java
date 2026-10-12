package co.com.ticketing.model.event;

import co.com.ticketing.model.exceptions.InvalidDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DATE = NOW.plusSeconds(86_400);

    @Test
    void createsAnEventWithItsWholeCapacityAvailable() {
        Event event = Event.create("e1", "Rock Fest", DATE, "Arena", 500, NOW);

        assertThat(event.capacity()).isEqualTo(500);
        assertThat(event.inventory()).isEqualTo(Inventory.ofCapacity(500));
        assertThat(event.createdAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Event.MAX_CAPACITY + 1})
    void capacityMustBeWithinLimits(int capacity) {
        assertThatThrownBy(() -> Event.create("e1", "Rock Fest", DATE, "Arena", capacity, NOW))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void dateMustBeInTheFuture() {
        assertThatThrownBy(() -> Event.create("e1", "Rock Fest", NOW, "Arena", 10, NOW))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("date");
        assertThatThrownBy(() -> Event.create("e1", "Rock Fest", null, "Arena", 10, NOW))
                .isInstanceOf(InvalidDataException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void textFieldsCannotBeBlank(String blank) {
        assertThatThrownBy(() -> Event.create(blank, "Rock Fest", DATE, "Arena", 10, NOW))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> Event.create("e1", blank, DATE, "Arena", 10, NOW))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> Event.create("e1", "Rock Fest", DATE, blank, 10, NOW))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("venue");
    }

    @Test
    void textFieldsHaveAMaximumLength() {
        String tooLong = "x".repeat(201);

        assertThatThrownBy(() -> Event.create("e1", tooLong, DATE, "Arena", 10, NOW))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> Event.create("e1", null, DATE, "Arena", 10, NOW))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    void everyFieldIsRequired() {
        Inventory inventory = Inventory.ofCapacity(1);

        assertThatThrownBy(() -> new Event("e1", "Rock Fest", null, "Arena", inventory, NOW))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> new Event("e1", "Rock Fest", DATE, "Arena", null, NOW))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("inventory");
        assertThatThrownBy(() -> new Event("e1", "Rock Fest", DATE, "Arena", inventory, null))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("createdAt");
    }
}
