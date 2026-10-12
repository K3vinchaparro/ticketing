package co.com.ticketing.sqs.listener;

import co.com.ticketing.usecase.order.ProcessOrderUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.json.JsonMapper;

@Log4j2
@Component
@RequiredArgsConstructor
public class OrderMessageProcessor {

    private static final String CORRELATION_ID = "correlationId";

    private final ProcessOrderUseCase processOrderUseCase;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    public Mono<Void> process(Message message) {
        return Mono.fromCallable(() -> orderIdOf(message))
                .flatMap(processOrderUseCase::process)
                .doOnNext(order -> {
                    meterRegistry.counter("orders.processed", "status", order.status().name()).increment();
                    log.info("Order {} processed: status={} correlationId={}",
                            order.orderId(), order.status(), correlationIdOf(message));
                })
                .then();
    }

    private String orderIdOf(Message message) {
        OrderMessage body = jsonMapper.readValue(message.body(), OrderMessage.class);
        if (body == null || body.orderId() == null || body.orderId().isBlank()) {
            throw new IllegalArgumentException("Message " + message.messageId() + " has no orderId");
        }
        return body.orderId();
    }

    private static String correlationIdOf(Message message) {
        MessageAttributeValue attribute = message.messageAttributes().get(CORRELATION_ID);
        return attribute == null ? "" : attribute.stringValue();
    }
}
