package co.com.ticketing.dynamodb;

import co.com.ticketing.model.exceptions.DuplicateOrderException;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.TransitionReason;
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
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static co.com.ticketing.dynamodb.TestData.NOW;
import static co.com.ticketing.dynamodb.TestData.cancelled;
import static co.com.ticketing.dynamodb.TestData.done;
import static co.com.ticketing.dynamodb.TestData.failed;
import static co.com.ticketing.dynamodb.TestData.item;
import static co.com.ticketing.dynamodb.TestData.order;
import static co.com.ticketing.dynamodb.TestData.orderRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoOrderRepositoryTest {

    private static final Instant LATER = NOW.plusSeconds(5);

    @Mock
    private DynamoDbAsyncClient client;

    private DynamoOrderRepository repository;

    @BeforeEach
    void setUp() {
        repository = orderRepository(client);
    }

    private void transactionSucceeds() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(done(TransactWriteItemsResponse.builder().build()));
    }

    private void transactionFails(Throwable error) {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(failed(error));
    }

    private List<TransactWriteItem> capturedWrites() {
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(request.capture());
        return request.getValue().transactItems();
    }

    private static OrderTransition transition(OrderStatus from, OrderStatus to) {
        return OrderTransition.of(order(from), to, LATER, LATER.plusSeconds(600), TransitionReason.TICKETS_RESERVED);
    }

    @Test
    void createWritesOrderIdempotencyKeyAndAuditAtomically() {
        Order order = order(OrderStatus.PENDING);
        transactionSucceeds();

        StepVerifier.create(repository.create(order, "key-1")).expectNext(order).verifyComplete();

        List<TransactWriteItem> writes = capturedWrites();
        assertThat(writes).hasSize(3);
        Put putOrder = writes.get(0).put();
        assertThat(putOrder.tableName()).isEqualTo("orders");
        assertThat(putOrder.conditionExpression()).isEqualTo("attribute_not_exists(orderId)");
        Put putKey = writes.get(1).put();
        assertThat(putKey.tableName()).isEqualTo("idempotency_keys");
        assertThat(putKey.conditionExpression()).isEqualTo("attribute_not_exists(idempotencyKey)");
        assertThat(putKey.item().get("idempotencyKey").s()).isEqualTo("c1#key-1");
        assertThat(putKey.item().get("orderId").s()).isEqualTo("o1");
        assertThat(putKey.item().get("expiresAt").n())
                .isEqualTo(Long.toString(NOW.plus(Duration.ofHours(24)).getEpochSecond()));
        assertThat(writes.get(2).put().tableName()).isEqualTo("ticket_audit");
    }

    @Test
    void createFailsAsDuplicateWhenTheKeyWasAlreadyUsed() {
        transactionFails(cancelled("None", "ConditionalCheckFailed", "None"));

        StepVerifier.create(repository.create(order(OrderStatus.PENDING), "key-1"))
                .verifyError(DuplicateOrderException.class);
    }

    @Test
    void createPropagatesACancellationThatIsNotADuplicate() {
        transactionFails(cancelled("ValidationError", "None", "None"));

        StepVerifier.withVirtualTime(() -> repository.create(order(OrderStatus.PENDING), "key-1"))
                .thenAwait(Duration.ofSeconds(30))
                .verifyError(TransactionCanceledException.class);
    }

    @Test
    void findsAnOrderWithAStronglyConsistentRead() {
        Order order = order(OrderStatus.RESERVED);
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(done(GetItemResponse.builder().item(item(order)).build()));

        StepVerifier.create(repository.findById("o1")).expectNext(order).verifyComplete();

        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo("orders");
        assertThat(request.getValue().consistentRead()).isTrue();
    }

    @Test
    void findByIdIsEmptyWhenTheOrderDoesNotExist() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(done(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findById("missing")).verifyComplete();
    }

    @Test
    void findsTheOrderBehindAnIdempotencyKey() {
        Order order = order(OrderStatus.PENDING);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(
                done(GetItemResponse.builder().item(Map.of(
                        "idempotencyKey", AttributeValue.fromS("c1#key-1"),
                        "orderId", AttributeValue.fromS("o1"))).build()),
                done(GetItemResponse.builder().item(item(order)).build()));

        StepVerifier.create(repository.findByIdempotencyKey("c1", "key-1")).expectNext(order).verifyComplete();

        ArgumentCaptor<GetItemRequest> requests = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, times(2)).getItem(requests.capture());
        assertThat(requests.getAllValues().get(0).tableName()).isEqualTo("idempotency_keys");
        assertThat(requests.getAllValues().get(0).key().get("idempotencyKey").s()).isEqualTo("c1#key-1");
    }

    @Test
    void findByIdempotencyKeyIsEmptyWhenTheKeyIsUnknown() {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(done(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findByIdempotencyKey("c1", "unknown")).verifyComplete();
    }

    @Test
    void findOverdueQueriesTheIndexWithABoundedBatch() {
        Order overdue = order(OrderStatus.RESERVED);
        when(client.query(any(QueryRequest.class)))
                .thenReturn(done(QueryResponse.builder().items(item(overdue)).build()));

        StepVerifier.create(repository.findOverdue(OrderStatus.RESERVED, NOW)).expectNext(overdue).verifyComplete();

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(request.capture());
        assertThat(request.getValue().indexName()).isEqualTo("status-expiresAt-index");
        assertThat(request.getValue().keyConditionExpression()).contains("<=");
        assertThat(request.getValue().limit()).isEqualTo(100);
        assertThat(request.getValue().expressionAttributeNames()).containsValues("status", "expiresAt");
        assertThat(request.getValue().expressionAttributeValues()).containsValues(
                AttributeValue.fromS("RESERVED"), AttributeValue.fromN(Long.toString(NOW.getEpochSecond())));
    }

    @Test
    void reservationUpdatesOrderInventoryAndAuditInOneTransaction() {
        OrderTransition reservation = transition(OrderStatus.PENDING, OrderStatus.RESERVED);
        transactionSucceeds();

        StepVerifier.create(repository.apply(reservation)).expectNext(reservation.to()).verifyComplete();

        List<TransactWriteItem> writes = capturedWrites();
        assertThat(writes).hasSize(3);

        Update order = writes.get(0).update();
        assertThat(order.tableName()).isEqualTo("orders");
        assertThat(order.conditionExpression()).isEqualTo("#status = :from");
        assertThat(order.expressionAttributeValues().get(":from").s()).isEqualTo("PENDING");
        assertThat(order.expressionAttributeValues().get(":to").s()).isEqualTo("RESERVED");
        assertThat(order.updateExpression()).contains("#expiresAt = :deadline").doesNotContain("REMOVE");
        assertThat(order.expressionAttributeValues().get(":deadline").n())
                .isEqualTo(Long.toString(LATER.plusSeconds(600).getEpochSecond()));

        Update inventory = writes.get(1).update();
        assertThat(inventory.tableName()).isEqualTo("events");
        assertThat(inventory.conditionExpression()).isEqualTo("#source >= :qty");
        assertThat(inventory.expressionAttributeNames())
                .containsEntry("#source", "available")
                .containsEntry("#target", "reserved");
        assertThat(inventory.expressionAttributeValues().get(":qty").n()).isEqualTo("2");

        assertThat(writes.get(2).put().tableName()).isEqualTo("ticket_audit");
    }

    @Test
    void finalTransitionRemovesTheDeadlineSoTheOrderLeavesTheIndex() {
        OrderTransition sale = transition(OrderStatus.PENDING_CONFIRMATION, OrderStatus.SOLD);
        transactionSucceeds();

        StepVerifier.create(repository.apply(sale)).expectNext(sale.to()).verifyComplete();

        List<TransactWriteItem> writes = capturedWrites();
        assertThat(writes.get(0).update().updateExpression()).endsWith("REMOVE #expiresAt");
        assertThat(writes.get(0).update().expressionAttributeValues()).doesNotContainKey(":deadline");
        assertThat(writes.get(1).update().expressionAttributeNames())
                .containsEntry("#source", "pendingConfirmation")
                .containsEntry("#target", "sold");
    }

    @Test
    void releaseReturnsTheTicketsToAvailable() {
        transactionSucceeds();

        StepVerifier.create(repository.apply(transition(OrderStatus.RESERVED, OrderStatus.RELEASED)))
                .expectNextCount(1).verifyComplete();

        assertThat(capturedWrites().get(1).update().expressionAttributeNames())
                .containsEntry("#source", "reserved")
                .containsEntry("#target", "available");
    }

    @Test
    void rejectionDoesNotTouchTheInventory() {
        transactionSucceeds();

        StepVerifier.create(repository.apply(transition(OrderStatus.PENDING, OrderStatus.REJECTED)))
                .expectNextCount(1).verifyComplete();

        List<TransactWriteItem> writes = capturedWrites();
        assertThat(writes).hasSize(2);
        assertThat(writes.get(0).update().tableName()).isEqualTo("orders");
        assertThat(writes.get(1).put().tableName()).isEqualTo("ticket_audit");
    }

    @Test
    void failedOrderConditionMeansAnotherProcessChangedTheOrder() {
        transactionFails(cancelled("ConditionalCheckFailed", "None", "None"));

        StepVerifier.create(repository.apply(transition(OrderStatus.PENDING, OrderStatus.RESERVED)))
                .verifyError(OrderConflictException.class);
    }

    @Test
    void failedInventoryConditionMeansTheEventIsSoldOut() {
        transactionFails(cancelled("None", "ConditionalCheckFailed", "None"));

        StepVerifier.create(repository.apply(transition(OrderStatus.PENDING, OrderStatus.RESERVED)))
                .verifyError(InsufficientTicketsException.class);
    }

    @Test
    void failedInventoryConditionOnHeldTicketsIsAnInvariantViolation() {
        transactionFails(cancelled("None", "ConditionalCheckFailed", "None"));

        StepVerifier.create(repository.apply(transition(OrderStatus.RESERVED, OrderStatus.RELEASED)))
                .verifyError(IllegalStateException.class);
    }

    @Test
    void transactionConflictIsRetriedAndThenSucceeds() {
        OrderTransition reservation = transition(OrderStatus.PENDING, OrderStatus.RESERVED);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(
                failed(cancelled("None", "TransactionConflict", "None")),
                done(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(repository.apply(reservation)).expectNext(reservation.to()).verifyComplete();

        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void cancellationWithoutConditionFailureIsPropagatedAfterRetries() {
        transactionFails(cancelled("None", "TransactionConflict", "None"));

        StepVerifier.withVirtualTime(() -> repository.apply(transition(OrderStatus.PENDING, OrderStatus.REJECTED)))
                .thenAwait(Duration.ofSeconds(30))
                .verifyError(TransactionCanceledException.class);
    }

    @Test
    void postponeMovesTheDeadlineIfTheStatusIsUnchanged() {
        Order pending = order(OrderStatus.PENDING);
        Instant newDeadline = NOW.plusSeconds(900);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(done(UpdateItemResponse.builder().build()));

        StepVerifier.create(repository.postpone(pending, newDeadline))
                .expectNext(pending.withDeadline(newDeadline))
                .verifyComplete();

        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client).updateItem(request.capture());
        assertThat(request.getValue().conditionExpression()).isEqualTo("#status = :status");
        assertThat(request.getValue().expressionAttributeValues().get(":status").s()).isEqualTo("PENDING");
        assertThat(request.getValue().expressionAttributeValues().get(":deadline").n())
                .isEqualTo(Long.toString(newDeadline.getEpochSecond()));
    }

    @Test
    void postponeFailsWithConflictWhenTheOrderChanged() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().statusCode(400).build()));

        StepVerifier.create(repository.postpone(order(OrderStatus.PENDING), NOW.plusSeconds(900)))
                .verifyError(OrderConflictException.class);
    }
}
