package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.TransitionReason;
import co.com.ticketing.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static co.com.ticketing.usecase.support.Fixtures.CLOCK;
import static co.com.ticketing.usecase.support.Fixtures.NOW;
import static co.com.ticketing.usecase.support.Fixtures.POLICY;
import static co.com.ticketing.usecase.support.Fixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessOrderUseCaseTest {

    @Mock
    private OrderRepository orderRepository;

    private ProcessOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessOrderUseCase(orderRepository, POLICY, CLOCK);
    }

    @Test
    void reservesTheTicketsForTheConfiguredTime() {
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(OrderStatus.PENDING)));
        when(orderRepository.apply(any()))
                .thenAnswer(invocation -> Mono.just(invocation.<OrderTransition>getArgument(0).to()));

        StepVerifier.create(useCase.process("o1"))
                .assertNext(order -> {
                    assertThat(order.status()).isEqualTo(OrderStatus.RESERVED);
                    assertThat(order.expiresAt()).isEqualTo(NOW.plus(POLICY.reservationTtl()));
                })
                .verifyComplete();

        verify(orderRepository).apply(argThat(transition -> transition.reason() == TransitionReason.TICKETS_RESERVED));
    }

    @Test
    void rejectsTheOrderWhenTheEventIsSoldOut() {
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(OrderStatus.PENDING)));
        when(orderRepository.apply(argThat(t -> t != null && t.to().status() == OrderStatus.RESERVED)))
                .thenReturn(Mono.error(new InsufficientTicketsException(2)));
        when(orderRepository.apply(argThat(t -> t != null && t.to().status() == OrderStatus.REJECTED)))
                .thenAnswer(invocation -> Mono.just(invocation.<OrderTransition>getArgument(0).to()));

        StepVerifier.create(useCase.process("o1"))
                .assertNext(order -> {
                    assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
                    assertThat(order.expiresAt()).isNull();
                })
                .verifyComplete();

        verify(orderRepository).apply(argThat(transition -> transition.reason() == TransitionReason.SOLD_OUT));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void duplicatedMessageIsIgnored(OrderStatus alreadyProcessed) {
        Order order = order(alreadyProcessed);
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.process("o1")).expectNext(order).verifyComplete();

        verify(orderRepository, never()).apply(any());
    }

    @Test
    void concurrentConsumerLosesTheRaceAndReturnsTheCurrentState() {
        Order reservedByOther = order(OrderStatus.RESERVED);
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(OrderStatus.PENDING)), Mono.just(reservedByOther));
        when(orderRepository.apply(any())).thenReturn(Mono.error(new OrderConflictException("o1")));

        StepVerifier.create(useCase.process("o1")).expectNext(reservedByOther).verifyComplete();
    }

    @Test
    void failsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.process("missing")).verifyError(OrderNotFoundException.class);
    }

    @Test
    void unexpectedErrorsArePropagated() {
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(OrderStatus.PENDING)));
        when(orderRepository.apply(any())).thenReturn(Mono.error(new IllegalStateException("database down")));

        StepVerifier.create(useCase.process("o1")).verifyError(IllegalStateException.class);
    }
}
