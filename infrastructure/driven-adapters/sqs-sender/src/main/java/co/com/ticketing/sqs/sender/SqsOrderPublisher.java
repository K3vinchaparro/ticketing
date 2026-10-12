package co.com.ticketing.sqs.sender;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.gateways.OrderPublisher;
import co.com.ticketing.sqs.sender.config.SqsSenderProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

@Log4j2
@Component
@RequiredArgsConstructor
public class SqsOrderPublisher implements OrderPublisher {

    public static final String CORRELATION_ID = "correlationId";

    private static final int MAX_RETRIES = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(100);

    private final SqsAsyncClient client;
    private final SqsSenderProperties properties;
    private final JsonMapper jsonMapper;

    @Override
    public Mono<Void> publish(Order order) {
        return Mono.deferContextual(context -> {
                    String correlationId = context.getOrDefault(CORRELATION_ID, "");
                    SendMessageRequest request = toRequest(order, correlationId);
                    return Mono.fromFuture(() -> client.sendMessage(request));
                })
                .retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF)
                        .filter(SqsOrderPublisher::isTransient)
                        .onRetryExhaustedThrow((_, signal) -> signal.failure()))
                .doOnNext(response -> log.debug("Order {} enqueued as message {}", order.orderId(), response.messageId()))
                .then();
    }

    private SendMessageRequest toRequest(Order order, String correlationId) {
        SendMessageRequest.Builder request = SendMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .messageBody(jsonMapper.writeValueAsString(new OrderMessage(order.orderId())));
        if (!correlationId.isBlank()) {
            request.messageAttributes(Map.of(CORRELATION_ID, MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(correlationId)
                    .build()));
        }
        return request.build();
    }

    private static boolean isTransient(Throwable error) {
        return switch (error) {
            case SqsException sqs -> sqs.isThrottlingException() || sqs.statusCode() >= 500;
            case SdkClientException _ -> true;
            default -> false;
        };
    }
}
