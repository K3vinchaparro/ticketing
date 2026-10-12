package co.com.ticketing.sqs.listener;

import co.com.ticketing.sqs.listener.config.SqsListenerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@Log4j2
@Component
@RequiredArgsConstructor
public class OrderQueueListener implements SmartLifecycle {

    private static final Duration RECEIVE_FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration RECEIVE_MAX_BACKOFF = Duration.ofSeconds(30);

    private final SqsAsyncClient client;
    private final SqsListenerProperties properties;
    private final OrderMessageProcessor processor;
    private final MeterRegistry meterRegistry;
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();

    @Override
    public void start() {
        if (properties.enabled() && subscription.get() == null) {
            subscription.set(poll().subscribe());
            log.info("Listening to queue {}", properties.queueUrl());
        }
    }

    @Override
    public void stop() {
        Disposable current = subscription.getAndSet(null);
        if (current != null) {
            current.dispose();
            log.info("Stopped listening to queue {}", properties.queueUrl());
        }
    }

    @Override
    public boolean isRunning() {
        return subscription.get() != null;
    }

    Flux<String> poll() {
        return receiveBatch()
                .repeat()
                .retryWhen(Retry.backoff(Long.MAX_VALUE, RECEIVE_FIRST_BACKOFF)
                        .maxBackoff(RECEIVE_MAX_BACKOFF)
                        .transientErrors(true)
                        .doBeforeRetry(signal -> log.warn("Receive failed, retrying", signal.failure())));
    }

    Flux<String> receiveBatch() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .maxNumberOfMessages(properties.maxNumberOfMessages())
                .waitTimeSeconds(properties.waitTimeSeconds())
                .visibilityTimeout(properties.visibilityTimeoutSeconds())
                .messageAttributeNames("All")
                .build();
        return Mono.fromFuture(() -> client.receiveMessage(request))
                .flatMapIterable(response -> response.messages())
                .flatMap(this::handle, properties.concurrency());
    }

    private Mono<String> handle(Message message) {
        return processor.process(message)
                .timeout(properties.processingTimeout())
                .then(delete(message))
                .thenReturn(message.messageId())
                .onErrorResume(error -> {
                    meterRegistry.counter("orders.processing.failures").increment();
                    log.warn("Message {} failed and will be redelivered", message.messageId(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> delete(Message message) {
        DeleteMessageRequest request = DeleteMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build();
        return Mono.fromFuture(() -> client.deleteMessage(request)).then();
    }
}
