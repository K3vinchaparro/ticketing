package co.com.ticketing.sqs.listener;

import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.usecase.order.ProcessOrderUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderMessageProcessorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Order RESERVED =
            new Order("o1", "e1", "c1", 2, OrderStatus.RESERVED, NOW.plusSeconds(600), NOW, NOW);

    @Mock
    private ProcessOrderUseCase processOrderUseCase;

    private SimpleMeterRegistry meterRegistry;
    private OrderMessageProcessor processor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processor = new OrderMessageProcessor(processOrderUseCase, JsonMapper.builder().build(), meterRegistry);
    }

    private static Message message(String body) {
        return Message.builder().messageId("m1").body(body).build();
    }

    @Test
    void processesTheOrderNamedInTheMessage() {
        when(processOrderUseCase.process("o1")).thenReturn(Mono.just(RESERVED));
        Message message = message("{\"orderId\":\"o1\"}").toBuilder()
                .messageAttributes(Map.of("correlationId",
                        MessageAttributeValue.builder().dataType("String").stringValue("corr-1").build()))
                .build();

        StepVerifier.create(processor.process(message)).verifyComplete();

        assertThat(meterRegistry.counter("orders.processed", "status", "RESERVED").count()).isEqualTo(1);
    }

    @Test
    void worksWithoutCorrelationId() {
        when(processOrderUseCase.process("o1")).thenReturn(Mono.just(RESERVED));

        StepVerifier.create(processor.process(message("{\"orderId\":\"o1\"}"))).verifyComplete();
    }

    @Test
    void useCaseFailureIsPropagated() {
        when(processOrderUseCase.process("o1")).thenReturn(Mono.error(new OrderNotFoundException("o1")));

        StepVerifier.create(processor.process(message("{\"orderId\":\"o1\"}")))
                .verifyError(OrderNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"orderId\":\"\"}", "{\"orderId\":null}", "null"})
    void messageWithoutOrderIdIsRejected(String body) {
        StepVerifier.create(processor.process(message(body))).verifyError(IllegalArgumentException.class);

        verifyNoInteractions(processOrderUseCase);
    }

    @Test
    void malformedMessageIsRejected() {
        StepVerifier.create(processor.process(message("not json"))).verifyError(JacksonException.class);

        verifyNoInteractions(processOrderUseCase);
    }
}
