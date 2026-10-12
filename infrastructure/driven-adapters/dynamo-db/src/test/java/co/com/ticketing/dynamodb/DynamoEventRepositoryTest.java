package co.com.ticketing.dynamodb;

import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.Map;

import static co.com.ticketing.dynamodb.TestData.NOW;
import static co.com.ticketing.dynamodb.TestData.cancelled;
import static co.com.ticketing.dynamodb.TestData.done;
import static co.com.ticketing.dynamodb.TestData.event;
import static co.com.ticketing.dynamodb.TestData.eventRepository;
import static co.com.ticketing.dynamodb.TestData.item;
import static co.com.ticketing.dynamodb.TestData.failed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoEventRepositoryTest {

    @Mock
    private DynamoDbAsyncClient client;

    private DynamoEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = eventRepository(client);
    }

    @Test
    void savesTheEventOnlyIfItDoesNotExist() {
        Event event = event(100);
        when(client.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder().build()));

        StepVerifier.create(repository.save(event)).expectNext(event).verifyComplete();

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo("events");
        assertThat(request.getValue().conditionExpression()).isEqualTo("attribute_not_exists(eventId)");
        assertThat(request.getValue().item().get("available").n()).isEqualTo("100");
    }

    @Test
    void saveFailsWhenTheEventAlreadyExists() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().statusCode(400).build()));

        StepVerifier.create(repository.save(event(100))).verifyError(ConditionalCheckFailedException.class);
    }

    @Test
    void findsAnEventWithAStronglyConsistentRead() {
        Event event = event(100);
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(done(GetItemResponse.builder().item(item(event)).build()));

        StepVerifier.create(repository.findById("e1")).expectNext(event).verifyComplete();

        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(request.capture());
        assertThat(request.getValue().consistentRead()).isTrue();
        assertThat(request.getValue().key()).isEqualTo(Map.of("eventId", AttributeValue.fromS("e1")));
    }

    @Test
    void findByIdIsEmptyWhenTheEventDoesNotExist() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(done(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findById("missing")).verifyComplete();
    }

    @Test
    void findAllFollowsEveryPage() {
        Map<String, AttributeValue> item = item(event(100));
        Map<String, AttributeValue> lastKey = Map.of("eventId", AttributeValue.fromS("e1"));
        when(client.scan(any(ScanRequest.class))).thenReturn(
                done(ScanResponse.builder().items(item).lastEvaluatedKey(lastKey).build()),
                done(ScanResponse.builder().items(item, item).build()));

        StepVerifier.create(repository.findAll()).expectNextCount(3).verifyComplete();

        ArgumentCaptor<ScanRequest> requests = ArgumentCaptor.forClass(ScanRequest.class);
        verify(client, times(2)).scan(requests.capture());
        assertThat(requests.getAllValues().get(0).hasExclusiveStartKey()).isFalse();
        assertThat(requests.getAllValues().get(1).exclusiveStartKey()).isEqualTo(lastKey);
    }

    @Test
    void complimentaryTicketsAreMovedAndAuditedInOneTransaction() {
        Event updated = event(100);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(done(TransactWriteItemsResponse.builder().build()));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(done(GetItemResponse.builder().item(item(updated)).build()));

        StepVerifier.create(repository.assignComplimentary("e1", 3, NOW)).expectNext(updated).verifyComplete();

        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(request.capture());
        assertThat(request.getValue().transactItems()).hasSize(2);
        Update update = request.getValue().transactItems().get(0).update();
        assertThat(update.tableName()).isEqualTo("events");
        assertThat(update.conditionExpression()).isEqualTo("#available >= :qty");
        assertThat(update.expressionAttributeNames())
                .containsEntry("#available", "available")
                .containsEntry("#complimentary", "complimentary");
        assertThat(update.expressionAttributeValues().get(":qty").n()).isEqualTo("3");
        assertThat(request.getValue().transactItems().get(1).put().tableName()).isEqualTo("ticket_audit");
    }

    @Test
    void complimentaryFailsWhenThereAreNotEnoughAvailableTickets() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(cancelled("ConditionalCheckFailed", "None")));

        StepVerifier.create(repository.assignComplimentary("e1", 3, NOW))
                .verifyError(InsufficientTicketsException.class);
    }

    @Test
    void complimentaryRetriesATransactionConflictAndThenSucceeds() {
        Event updated = event(100);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                failed(cancelled("TransactionConflict", "None")),
                done(TransactWriteItemsResponse.builder().build()));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(done(GetItemResponse.builder().item(item(updated)).build()));

        StepVerifier.create(repository.assignComplimentary("e1", 3, NOW)).expectNext(updated).verifyComplete();

        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void complimentaryPropagatesACancellationThatIsNotAConditionFailure() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(cancelled("ValidationError", "None")));

        StepVerifier.withVirtualTime(() -> repository.assignComplimentary("e1", 3, NOW))
                .thenAwait(java.time.Duration.ofSeconds(30))
                .verifyError(TransactionCanceledException.class);
    }
}
