package co.com.ticketing.sqs.sender;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.sqs.sender.config.SqsSenderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsOrderPublisherTest {

    private static final String QUEUE_URL = "http://localhost:9324/000000000000/orders-queue";
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Order ORDER = Order.pending("o1", "e1", "c1", 2, NOW, NOW.plusSeconds(120));
    private static final CompletableFuture<SendMessageResponse> SENT =
            CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m1").build());

    @Mock
    private SqsAsyncClient client;

    private SqsOrderPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SqsOrderPublisher(client, new SqsSenderProperties(QUEUE_URL), JsonMapper.builder().build());
    }

    private SendMessageRequest sentRequest() {
        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(request.capture());
        return request.getValue();
    }

    @Test
    void sendsOnlyTheOrderIdToTheQueue() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(SENT);

        StepVerifier.create(publisher.publish(ORDER)).verifyComplete();

        SendMessageRequest request = sentRequest();
        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.messageBody()).isEqualTo("{\"orderId\":\"o1\"}");
        assertThat(request.hasMessageAttributes()).isFalse();
    }

    @Test
    void propagatesTheCorrelationIdFromTheReactorContext() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(SENT);

        StepVerifier.create(publisher.publish(ORDER)
                        .contextWrite(context -> context.put(SqsOrderPublisher.CORRELATION_ID, "corr-123")))
                .verifyComplete();

        assertThat(sentRequest().messageAttributes().get("correlationId").stringValue()).isEqualTo("corr-123");
    }

    @Test
    void transientFailureIsRetried() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(
                CompletableFuture.failedFuture(SdkClientException.create("connection reset")),
                CompletableFuture.failedFuture(SqsException.builder().statusCode(503).build()),
                SENT);

        StepVerifier.withVirtualTime(() -> publisher.publish(ORDER))
                .thenAwait(Duration.ofSeconds(10))
                .verifyComplete();

        verify(client, times(3)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void failsWithTheOriginalErrorWhenRetriesAreExhausted() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("connection reset")));

        StepVerifier.withVirtualTime(() -> publisher.publish(ORDER))
                .thenAwait(Duration.ofSeconds(30))
                .verifyError(SdkClientException.class);

        verify(client, times(4)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void permanentFailureIsNotRetried() {
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SqsException.builder().statusCode(400).build()),
                        CompletableFuture.failedFuture(new IllegalStateException("bug")));

        StepVerifier.create(publisher.publish(ORDER)).verifyError(SqsException.class);
        StepVerifier.create(publisher.publish(ORDER)).verifyError(IllegalStateException.class);

        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
    }
}
