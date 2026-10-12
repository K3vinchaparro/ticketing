package co.com.ticketing.model.order;

import co.com.ticketing.model.exceptions.InvalidDataException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPolicyTest {

    private static final Duration OK = Duration.ofMinutes(1);

    @Test
    void acceptsPositiveValues() {
        OrderPolicy policy = new OrderPolicy(Duration.ofMinutes(10), OK, OK, 6);

        assertThat(policy.reservationTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.maxTicketsPerOrder()).isEqualTo(6);
    }

    @Test
    void durationsMustBePositive() {
        Duration negative = Duration.ofSeconds(-1);

        assertThatThrownBy(() -> new OrderPolicy(null, OK, OK, 6)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> new OrderPolicy(OK, Duration.ZERO, OK, 6)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> new OrderPolicy(OK, OK, negative, 6)).isInstanceOf(InvalidDataException.class);
    }

    @Test
    void maxTicketsMustBeAtLeastOne() {
        assertThatThrownBy(() -> new OrderPolicy(OK, OK, OK, 0)).isInstanceOf(InvalidDataException.class);
    }
}
