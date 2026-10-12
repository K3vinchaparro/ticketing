package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.gateways.OrderPublisher;
import co.com.ticketing.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static co.com.ticketing.usecase.support.Fixtures.CLOCK;
import static co.com.ticketing.usecase.support.Fixtures.NOW;
import static co.com.ticketing.usecase.support.Fixtures.POLICY;
import static co.com.ticketing.usecase.support.Fixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepublishPendingOrdersUseCaseTest {

    private static final Instant NEXT_DEADLINE = NOW.plus(POLICY.processingTimeout());

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderPublisher orderPublisher;

    private RepublishPendingOrdersUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RepublishPendingOrdersUseCase(orderRepository, orderPublisher, POLICY, CLOCK);
    }

    @Test
    void enqueuesAgainOrdersStuckInPendingAndPushesTheirDeadline() {
        Order stuck = order(OrderStatus.PENDING);
        when(orderRepository.findOverdue(OrderStatus.PENDING, NOW)).thenReturn(Flux.just(stuck));
        when(orderPublisher.publish(stuck)).thenReturn(Mono.empty());
        when(orderRepository.postpone(stuck, NEXT_DEADLINE)).thenReturn(Mono.just(stuck.withDeadline(NEXT_DEADLINE)));

        StepVerifier.create(useCase.execute())
                .assertNext(order -> assertThat(order.expiresAt()).isEqualTo(NEXT_DEADLINE))
                .verifyComplete();

        verify(orderPublisher).publish(stuck);
    }

    @Test
    void ordersProcessedInTheMeantimeAreSkipped() {
        Order stuck = order(OrderStatus.PENDING);
        when(orderRepository.findOverdue(OrderStatus.PENDING, NOW)).thenReturn(Flux.just(stuck));
        when(orderPublisher.publish(stuck)).thenReturn(Mono.empty());
        when(orderRepository.postpone(stuck, NEXT_DEADLINE)).thenReturn(Mono.error(new OrderConflictException("o1")));

        StepVerifier.create(useCase.execute()).verifyComplete();
    }

    @Test
    void deadlineIsNotMovedWhenPublishingFails() {
        Order stuck = order(OrderStatus.PENDING);
        when(orderRepository.findOverdue(OrderStatus.PENDING, NOW)).thenReturn(Flux.just(stuck));
        when(orderPublisher.publish(stuck)).thenReturn(Mono.error(new IllegalStateException("queue down")));

        StepVerifier.create(useCase.execute()).verifyError(IllegalStateException.class);

        verify(orderRepository, never()).postpone(any(), any());
    }
}
