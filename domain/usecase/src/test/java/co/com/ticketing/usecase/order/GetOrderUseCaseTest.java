package co.com.ticketing.usecase.order;

import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static co.com.ticketing.usecase.support.Fixtures.order;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetOrderUseCaseTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private GetOrderUseCase useCase;

    @Test
    void returnsTheOrder() {
        Order order = order(OrderStatus.RESERVED);
        when(orderRepository.findById("o1")).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.getById("o1")).expectNext(order).verifyComplete();
    }

    @Test
    void failsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.getById("missing")).verifyError(OrderNotFoundException.class);
    }
}
