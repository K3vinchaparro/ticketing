package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.TransitionReason;
import co.com.ticketing.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static co.com.ticketing.usecase.support.Fixtures.CLOCK;
import static co.com.ticketing.usecase.support.Fixtures.NOW;
import static co.com.ticketing.usecase.support.Fixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseExpiredReservationsUseCaseTest {

    @Mock
    private OrderRepository orderRepository;

    private ReleaseExpiredReservationsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ReleaseExpiredReservationsUseCase(orderRepository, CLOCK);
    }

    @Test
    void releasesExpiredReservationsAndStuckPayments() {
        when(orderRepository.findOverdue(OrderStatus.RESERVED, NOW))
                .thenReturn(Flux.just(order("o1", OrderStatus.RESERVED, 2)));
        when(orderRepository.findOverdue(OrderStatus.PENDING_CONFIRMATION, NOW))
                .thenReturn(Flux.just(order("o2", OrderStatus.PENDING_CONFIRMATION, 1)));
        when(orderRepository.apply(any()))
                .thenAnswer(invocation -> Mono.just(invocation.<OrderTransition>getArgument(0).to()));

        StepVerifier.create(useCase.execute())
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.RELEASED))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.RELEASED))
                .verifyComplete();

        ArgumentCaptor<OrderTransition> transitions = ArgumentCaptor.forClass(OrderTransition.class);
        verify(orderRepository, times(2)).apply(transitions.capture());
        assertThat(transitions.getAllValues()).extracting(OrderTransition::reason)
                .containsExactly(TransitionReason.RESERVATION_EXPIRED, TransitionReason.PAYMENT_TIMEOUT);
    }

    @Test
    void ordersChangedByAnotherProcessAreSkipped() {
        when(orderRepository.findOverdue(OrderStatus.RESERVED, NOW))
                .thenReturn(Flux.just(order("o1", OrderStatus.RESERVED, 2), order("o2", OrderStatus.RESERVED, 1)));
        when(orderRepository.findOverdue(OrderStatus.PENDING_CONFIRMATION, NOW)).thenReturn(Flux.empty());
        when(orderRepository.apply(argThat(t -> t != null && t.to().orderId().equals("o1"))))
                .thenReturn(Mono.error(new OrderConflictException("o1")));
        when(orderRepository.apply(argThat(t -> t != null && t.to().orderId().equals("o2"))))
                .thenAnswer(invocation -> Mono.just(invocation.<OrderTransition>getArgument(0).to()));

        StepVerifier.create(useCase.execute())
                .assertNext(order -> assertThat(order.orderId()).isEqualTo("o2"))
                .verifyComplete();
    }

    @Test
    void doesNothingWhenNoOrderIsOverdue() {
        when(orderRepository.findOverdue(any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(useCase.execute()).verifyComplete();

        verify(orderRepository, never()).apply(any());
    }
}
