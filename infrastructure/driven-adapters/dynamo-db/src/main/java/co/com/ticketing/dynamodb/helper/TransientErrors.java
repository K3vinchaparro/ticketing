package co.com.ticketing.dynamodb.helper;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;

import java.time.Duration;
import java.util.List;

public final class TransientErrors {

    private static final String CONDITION_FAILED = "ConditionalCheckFailed";
    private static final int MAX_RETRIES = 4;
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(50);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(1);

    private TransientErrors() {
    }

    public static <T> Mono<T> retrying(Mono<T> call) {
        return call.retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .jitter(0.5)
                .filter(TransientErrors::isTransient)
                .onRetryExhaustedThrow((_, signal) -> signal.failure()));
    }

    public static boolean isTransient(Throwable error) {
        return switch (error) {
            case TransactionCanceledException canceled -> !hasConditionFailure(canceled);
            case ProvisionedThroughputExceededException _, RequestLimitExceededException _,
                 TransactionConflictException _ -> true;
            case DynamoDbException dynamo -> dynamo.isThrottlingException() || dynamo.statusCode() >= 500;
            case SdkClientException _ -> true;
            default -> false;
        };
    }

    public static boolean conditionFailedAt(TransactionCanceledException error, int index) {
        List<CancellationReason> reasons = error.cancellationReasons();
        return index < reasons.size() && CONDITION_FAILED.equals(reasons.get(index).code());
    }

    private static boolean hasConditionFailure(TransactionCanceledException error) {
        return error.cancellationReasons().stream().anyMatch(reason -> CONDITION_FAILED.equals(reason.code()));
    }
}
