package co.com.ticketing.sqs.listener;

import co.com.ticketing.sqs.listener.config.SqsListenerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQueueListenerTest {

    private static final String QUEUE_URL = "http://localhost:9324/000000000000/orders-queue";
    private static final Message FIRST = Message.builder().messageId("m1").receiptHandle("r1").body("{}").build();
    private static final Message SECOND = Message.builder().messageId("m2").receiptHandle("r2").body("{}").build();

    @Mock
    private SqsAsyncClient client;
    @Mock
    private OrderMessageProcessor processor;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private OrderQueueListener listener(boolean enabled, Duration processingTimeout) {
        SqsListenerProperties properties =
                new SqsListenerProperties(enabled, QUEUE_URL, 20, 30, 10, 5, processingTimeout);
        return new OrderQueueListener(client, properties, processor, meterRegistry);
    }

    private OrderQueueListener listener() {
        return listener(true, Duration.ofSeconds(20));
    }

    private void queueReturns(Message... messages) {
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(
                CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(messages).build()));
    }

    private void deleteSucceeds() {
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
    }

    @Test
    void receivesWithLongPollingAndDeletesProcessedMessages() {
        queueReturns(FIRST, SECOND);
        deleteSucceeds();
        when(processor.process(any())).thenReturn(Mono.empty());

        StepVerifier.create(listener().receiveBatch()).expectNext("m1", "m2").verifyComplete();

        ArgumentCaptor<ReceiveMessageRequest> receive = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(receive.capture());
        assertThat(receive.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(receive.getValue().waitTimeSeconds()).isEqualTo(20);
        assertThat(receive.getValue().visibilityTimeout()).isEqualTo(30);
        assertThat(receive.getValue().maxNumberOfMessages()).isEqualTo(10);

        ArgumentCaptor<DeleteMessageRequest> delete = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(client, org.mockito.Mockito.times(2)).deleteMessage(delete.capture());
        assertThat(delete.getAllValues()).extracting(DeleteMessageRequest::receiptHandle)
                .containsExactlyInAnyOrder("r1", "r2");
    }

    @Test
    void failedMessageIsNotDeletedAndDoesNotStopTheBatch() {
        queueReturns(FIRST, SECOND);
        deleteSucceeds();
        when(processor.process(FIRST)).thenReturn(Mono.error(new IllegalStateException("database down")));
        when(processor.process(SECOND)).thenReturn(Mono.empty());

        StepVerifier.create(listener().receiveBatch()).expectNext("m2").verifyComplete();

        ArgumentCaptor<DeleteMessageRequest> delete = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(client).deleteMessage(delete.capture());
        assertThat(delete.getValue().receiptHandle()).isEqualTo("r2");
        assertThat(meterRegistry.counter("orders.processing.failures").count()).isEqualTo(1);
    }

    @Test
    void messageThatTakesTooLongIsLeftForRedelivery() {
        queueReturns(FIRST);
        when(processor.process(FIRST)).thenReturn(Mono.never());

        StepVerifier.create(listener(true, Duration.ofMillis(50)).receiveBatch()).verifyComplete();

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void emptyQueueCompletesTheBatch() {
        queueReturns();

        StepVerifier.create(listener().receiveBatch()).verifyComplete();

        verifyNoInteractions(processor);
    }

    @Test
    void keepsPollingAfterAReceiveFailure() {
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(
                CompletableFuture.failedFuture(SdkClientException.create("connection reset")),
                CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(FIRST).build()));
        deleteSucceeds();
        when(processor.process(any())).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(() -> listener().poll().take(1))
                .thenAwait(Duration.ofSeconds(5))
                .expectNext("m1")
                .verifyComplete();
    }

    @Test
    void startsAndStopsWithTheApplication() {
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(new CompletableFuture<>());
        OrderQueueListener listener = listener();

        assertThat(listener.isRunning()).isFalse();
        listener.start();
        listener.start();
        assertThat(listener.isRunning()).isTrue();
        listener.stop();
        listener.stop();
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void doesNotStartWhenDisabled() {
        OrderQueueListener listener = listener(false, Duration.ofSeconds(20));

        listener.start();

        assertThat(listener.isRunning()).isFalse();
        verifyNoInteractions(client);
    }
}
