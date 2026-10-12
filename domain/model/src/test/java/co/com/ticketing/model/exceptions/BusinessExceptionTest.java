package co.com.ticketing.model.exceptions;

import co.com.ticketing.model.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void everyExceptionExplainsWhatHappened() {
        List<BusinessException> exceptions = List.of(
                new DuplicateOrderException("key-1"),
                new EventNotFoundException("e1"),
                new OrderNotFoundException("o1"),
                new IdempotencyKeyReuseException("key-1"),
                new InsufficientTicketsException(4),
                new InvalidDataException("quantity must be at least 1"),
                new InvalidTransitionException(OrderStatus.SOLD, OrderStatus.RELEASED),
                new OrderConflictException("o1"),
                new PaymentDeclinedException("o1"),
                new ReservationExpiredException("o1"));

        assertThat(exceptions).extracting(Throwable::getMessage).containsExactly(
                "An order already exists for idempotency key key-1",
                "Event not found: e1",
                "Order not found: o1",
                "Idempotency key key-1 was already used with a different request",
                "Not enough tickets to fulfil a request of 4",
                "quantity must be at least 1",
                "Transition not allowed: SOLD -> RELEASED",
                "Order o1 was modified by another process",
                "Payment declined for order o1",
                "Reservation expired for order o1");
    }
}
