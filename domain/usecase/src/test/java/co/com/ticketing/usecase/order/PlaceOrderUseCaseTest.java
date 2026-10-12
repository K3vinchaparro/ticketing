package co.com.ticketing.usecase.order;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.DuplicateOrderException;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import co.com.ticketing.model.exceptions.IdempotencyKeyReuseException;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.PlaceOrderCommand;
import co.com.ticketing.model.order.gateways.OrderPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static co.com.ticketing.usecase.support.Fixtures.CLOCK;
import static co.com.ticketing.usecase.support.Fixtures.NOW;
import static co.com.ticketing.usecase.support.Fixtures.POLICY;
import static co.com.ticketing.usecase.support.Fixtures.event;
import static co.com.ticketing.usecase.support.Fixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceOrderUseCaseTest {

    private static final PlaceOrderCommand COMMAND = new PlaceOrderCommand("e1", "c1", 2, "key-12345");

    @Mock
    private EventRepository eventRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderPublisher orderPublisher;

    private PlaceOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PlaceOrderUseCase(eventRepository, orderRepository, orderPublisher, POLICY, CLOCK);
    }

    @Test
    void storesTheOrderAsPendingAndEnqueuesIt() {
        when(orderRepository.findByIdempotencyKey("c1", "key-12345")).thenReturn(Mono.empty());
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event("e1", 10)));
        when(orderRepository.create(any(), eq("key-12345")))
                .thenAnswer(invocation -> Mono.just(invocation.<Order>getArgument(0)));
        when(orderPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(COMMAND))
                .assertNext(placed -> {
                    assertThat(placed.replayed()).isFalse();
                    assertThat(placed.order().status()).isEqualTo(OrderStatus.PENDING);
                    assertThat(placed.order().quantity()).isEqualTo(2);
                    assertThat(placed.order().expiresAt()).isEqualTo(NOW.plus(POLICY.processingTimeout()));
                })
                .verifyComplete();

        verify(orderPublisher).publish(any());
    }

    @Test
    void repeatedRequestReturnsTheExistingOrderWithoutCreatingAnother() {
        Order existing = order(OrderStatus.RESERVED);
        when(orderRepository.findByIdempotencyKey("c1", "key-12345")).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.place(COMMAND))
                .assertNext(placed -> {
                    assertThat(placed.replayed()).isTrue();
                    assertThat(placed.order()).isEqualTo(existing);
                })
                .verifyComplete();

        verify(orderRepository, never()).create(any(), any());
        verifyNoInteractions(orderPublisher, eventRepository);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        when(orderRepository.findByIdempotencyKey("c1", "key-12345")).thenReturn(Mono.just(order(OrderStatus.PENDING)));
        PlaceOrderCommand different = new PlaceOrderCommand("e1", "c1", 5, "key-12345");

        StepVerifier.create(useCase.place(different)).verifyError(IdempotencyKeyReuseException.class);
    }

    @Test
    void concurrentDuplicateReturnsTheOrderCreatedByTheWinner() {
        Order winner = order(OrderStatus.PENDING);
        when(orderRepository.findByIdempotencyKey("c1", "key-12345")).thenReturn(Mono.empty(), Mono.just(winner));
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event("e1", 10)));
        when(orderRepository.create(any(), eq("key-12345")))
                .thenReturn(Mono.error(new DuplicateOrderException("key-12345")));

        StepVerifier.create(useCase.place(COMMAND))
                .assertNext(placed -> {
                    assertThat(placed.replayed()).isTrue();
                    assertThat(placed.order()).isEqualTo(winner);
                })
                .verifyComplete();

        verifyNoInteractions(orderPublisher);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void rejectsUpFrontWithoutQueuingWhenThereAreNotEnoughAvailableTickets(int available) {
        Event template = event("e1", 10);
        Event almostSoldOut = new Event(template.eventId(), template.name(), template.date(), template.venue(),
                new Inventory(available, 0, 0, 10 - available, 0), template.createdAt());
        when(orderRepository.findByIdempotencyKey("c1", "key-12345")).thenReturn(Mono.empty());
        when(eventRepository.findById("e1")).thenReturn(Mono.just(almostSoldOut));

        StepVerifier.create(useCase.place(COMMAND)).verifyError(InsufficientTicketsException.class);

        verify(orderRepository, never()).create(any(), any());
        verifyNoInteractions(orderPublisher);
    }

    @Test
    void failsWhenTheEventDoesNotExist() {
        when(orderRepository.findByIdempotencyKey("c1", "key-12345")).thenReturn(Mono.empty());
        when(eventRepository.findById("e1")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(COMMAND)).verifyError(EventNotFoundException.class);

        verify(orderRepository, never()).create(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 7})
    void quantityMustRespectThePolicyLimit(int quantity) {
        StepVerifier.create(useCase.place(new PlaceOrderCommand("e1", "c1", quantity, "key-12345")))
                .verifyError(InvalidDataException.class);

        verifyNoInteractions(orderRepository, orderPublisher, eventRepository);
    }

    @Test
    void publishFailureIsPropagatedToTheCaller() {
        when(orderRepository.findByIdempotencyKey("c1", "key-12345")).thenReturn(Mono.empty());
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event("e1", 10)));
        when(orderRepository.create(any(), any()))
                .thenAnswer(invocation -> Mono.just(invocation.<Order>getArgument(0)));
        when(orderPublisher.publish(any())).thenReturn(Mono.error(new IllegalStateException("queue down")));

        StepVerifier.create(useCase.place(COMMAND)).verifyError(IllegalStateException.class);
    }
}
