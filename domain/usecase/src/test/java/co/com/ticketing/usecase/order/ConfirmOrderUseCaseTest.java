package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.exceptions.PaymentDeclinedException;
import co.com.ticketing.model.exceptions.ReservationExpiredException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.PaymentResult;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.order.gateways.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static co.com.ticketing.usecase.support.Fixtures.CLOCK;
import static co.com.ticketing.usecase.support.Fixtures.NOW;
import static co.com.ticketing.usecase.support.Fixtures.POLICY;
import static co.com.ticketing.usecase.support.Fixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmOrderUseCaseTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentGateway paymentGateway;

    private ConfirmOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ConfirmOrderUseCase(orderRepository, paymentGateway, POLICY, CLOCK);
    }

    private void repositoryAppliesEveryTransition() {
        when(orderRepository.apply(any()))
                .thenAnswer(invocation -> Mono.just(invocation.<OrderTransition>getArgument(0).to()));
    }

    @Test
    void approvedPaymentSellsTheTickets() {
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(OrderStatus.RESERVED)));
        repositoryAppliesEveryTransition();
        when(paymentGateway.charge(any())).thenReturn(Mono.just(PaymentResult.APPROVED));

        StepVerifier.create(useCase.confirm("o1"))
                .assertNext(order -> {
                    assertThat(order.status()).isEqualTo(OrderStatus.SOLD);
                    assertThat(order.expiresAt()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<OrderTransition> transitions = ArgumentCaptor.forClass(OrderTransition.class);
        verify(orderRepository, times(2)).apply(transitions.capture());
        assertThat(transitions.getAllValues()).extracting(transition -> transition.to().status())
                .containsExactly(OrderStatus.PENDING_CONFIRMATION, OrderStatus.SOLD);
        assertThat(transitions.getAllValues().getFirst().to().expiresAt())
                .isEqualTo(NOW.plus(POLICY.paymentTimeout()));
    }

    @Test
    void declinedPaymentReleasesTheTicketsAndReportsTheDecline() {
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(OrderStatus.RESERVED)));
        repositoryAppliesEveryTransition();
        when(paymentGateway.charge(any())).thenReturn(Mono.just(PaymentResult.DECLINED));

        StepVerifier.create(useCase.confirm("o1")).verifyError(PaymentDeclinedException.class);

        ArgumentCaptor<OrderTransition> transitions = ArgumentCaptor.forClass(OrderTransition.class);
        verify(orderRepository, times(2)).apply(transitions.capture());
        assertThat(transitions.getAllValues().getLast().to().status()).isEqualTo(OrderStatus.RELEASED);
    }

    @Test
    void expiredReservationCannotBeConfirmed() {
        Order expired = order(OrderStatus.RESERVED).withDeadline(NOW.minus(Duration.ofSeconds(1)));
        when(orderRepository.findById("o1")).thenReturn(Mono.just(expired));

        StepVerifier.create(useCase.confirm("o1")).verifyError(ReservationExpiredException.class);

        verifyNoInteractions(paymentGateway);
    }

    @Test
    void losingTheRaceAgainstTheReleaseJobDoesNotCharge() {
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(OrderStatus.RESERVED)));
        when(orderRepository.apply(any())).thenReturn(Mono.error(new OrderConflictException("o1")));

        StepVerifier.create(useCase.confirm("o1")).verifyError(OrderConflictException.class);

        verifyNoInteractions(paymentGateway);
    }

    @Test
    void confirmingASoldOrderIsIdempotent() {
        Order sold = order(OrderStatus.SOLD);
        when(orderRepository.findById("o1")).thenReturn(Mono.just(sold));

        StepVerifier.create(useCase.confirm("o1")).expectNext(sold).verifyComplete();

        verifyNoInteractions(paymentGateway);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "PENDING_CONFIRMATION", "REJECTED", "RELEASED"})
    void onlyReservedOrdersCanBeConfirmed(OrderStatus status) {
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(status)));

        StepVerifier.create(useCase.confirm("o1")).verifyError(InvalidTransitionException.class);

        verifyNoInteractions(paymentGateway);
    }

    @Test
    void failsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.confirm("missing")).verifyError(OrderNotFoundException.class);
    }

    @Test
    void paymentProviderFailureLeavesTheOrderInPayment() {
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order(OrderStatus.RESERVED)));
        repositoryAppliesEveryTransition();
        when(paymentGateway.charge(any())).thenReturn(Mono.error(new IllegalStateException("provider down")));

        StepVerifier.create(useCase.confirm("o1")).verifyError(IllegalStateException.class);

        verify(orderRepository, times(1)).apply(any());
    }
}
