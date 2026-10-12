package co.com.ticketing.dynamodb.helper;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransientErrorsTest {

    private static TransactionCanceledException cancelled(String... codes) {
        return TransactionCanceledException.builder()
                .cancellationReasons(java.util.Arrays.stream(codes)
                        .map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }

    @Test
    void conflictsThrottlingTimeoutsAndServerErrorsAreTransient() {
        assertThat(TransientErrors.isTransient(cancelled("None", "TransactionConflict"))).isTrue();
        assertThat(TransientErrors.isTransient(ProvisionedThroughputExceededException.builder().build())).isTrue();
        assertThat(TransientErrors.isTransient(DynamoDbException.builder().statusCode(503).build())).isTrue();
        assertThat(TransientErrors.isTransient(TransactionConflictException.builder().build())).isTrue();
        assertThat(TransientErrors.isTransient(SdkClientException.create("timeout"))).isTrue();
    }

    @Test
    void failedConditionsAndClientErrorsAreNotTransient() {
        assertThat(TransientErrors.isTransient(cancelled("ConditionalCheckFailed", "None"))).isFalse();
        assertThat(TransientErrors.isTransient(ConditionalCheckFailedException.builder().statusCode(400).build()))
                .isFalse();
        assertThat(TransientErrors.isTransient(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void conditionFailedAtReadsTheReasonOfEachWrite() {
        TransactionCanceledException error = cancelled("None", "ConditionalCheckFailed");

        assertThat(TransientErrors.conditionFailedAt(error, 0)).isFalse();
        assertThat(TransientErrors.conditionFailedAt(error, 1)).isTrue();
        assertThat(TransientErrors.conditionFailedAt(error, 2)).isFalse();
    }

    @Test
    void transientErrorIsRetriedUntilItSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> attempts.incrementAndGet() < 3
                ? Mono.error(SdkClientException.create("timeout"))
                : Mono.just("ok"));

        StepVerifier.withVirtualTime(() -> TransientErrors.retrying(call))
                .thenAwait(Duration.ofSeconds(10))
                .expectNext("ok")
                .verifyComplete();
        assertThat(attempts).hasValue(3);
    }

    @Test
    void originalErrorIsRaisedWhenRetriesAreExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(SdkClientException.create("timeout"));
        });

        StepVerifier.withVirtualTime(() -> TransientErrors.retrying(call))
                .thenAwait(Duration.ofSeconds(30))
                .verifyError(SdkClientException.class);
        assertThat(attempts).hasValue(5);
    }

    @Test
    void businessErrorIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(cancelled("ConditionalCheckFailed"));
        });

        StepVerifier.create(TransientErrors.retrying(call)).verifyError(TransactionCanceledException.class);
        assertThat(attempts).hasValue(1);
    }
}
