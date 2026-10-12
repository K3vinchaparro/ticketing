package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.ticketing.dynamodb.entity.AuditEntity;
import co.com.ticketing.dynamodb.entity.EventEntity;
import co.com.ticketing.dynamodb.entity.IdempotencyKeyEntity;
import co.com.ticketing.dynamodb.entity.OrderEntity;
import co.com.ticketing.dynamodb.mapper.AuditEntityMapper;
import co.com.ticketing.dynamodb.mapper.OrderEntityMapper;
import co.com.ticketing.model.exceptions.DuplicateOrderException;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static co.com.ticketing.dynamodb.entity.EventEntity.EVENT_ID;
import static co.com.ticketing.dynamodb.entity.EventEntity.counterOf;
import static co.com.ticketing.dynamodb.entity.IdempotencyKeyEntity.IDEMPOTENCY_KEY;
import static co.com.ticketing.dynamodb.entity.OrderEntity.EXPIRES_AT;
import static co.com.ticketing.dynamodb.entity.OrderEntity.ORDER_ID;
import static co.com.ticketing.dynamodb.entity.OrderEntity.OVERDUE_INDEX;
import static co.com.ticketing.dynamodb.entity.OrderEntity.STATUS;
import static co.com.ticketing.dynamodb.entity.OrderEntity.UPDATED_AT;
import static co.com.ticketing.dynamodb.helper.Attributes.epochSeconds;
import static co.com.ticketing.dynamodb.helper.Attributes.number;
import static co.com.ticketing.dynamodb.helper.Attributes.string;
import static co.com.ticketing.dynamodb.helper.Attributes.timestamp;
import static co.com.ticketing.dynamodb.helper.TransientErrors.conditionFailedAt;
import static co.com.ticketing.dynamodb.helper.TransientErrors.retrying;

@Repository
@RequiredArgsConstructor
public class DynamoOrderRepository implements OrderRepository {

    private static final int ORDER_WRITE = 0;
    private static final int KEY_WRITE = 1;
    private static final int INVENTORY_WRITE = 1;

    private final DynamoDbAsyncClient client;
    private final DynamoDbAsyncTable<OrderEntity> orders;
    private final DynamoDbAsyncTable<IdempotencyKeyEntity> idempotencyKeys;
    private final DynamoDbAsyncTable<EventEntity> events;
    private final DynamoDbAsyncTable<AuditEntity> audit;
    private final DynamoDbProperties properties;

    @Override
    public Mono<Order> create(Order order, String idempotencyKey) {
        IdempotencyKeyEntity key = IdempotencyKeyEntity.builder()
                .idempotencyKey(scopedKey(order.customerId(), idempotencyKey))
                .orderId(order.orderId())
                .expiresAt(order.createdAt().plus(properties.idempotencyTtl()).getEpochSecond())
                .build();
        Put putOrder = Put.builder()
                .tableName(orders.tableName())
                .item(orders.tableSchema().itemToMap(OrderEntityMapper.toEntity(order), true))
                .conditionExpression("attribute_not_exists(" + ORDER_ID + ")")
                .build();
        Put putKey = Put.builder()
                .tableName(idempotencyKeys.tableName())
                .item(idempotencyKeys.tableSchema().itemToMap(key, true))
                .conditionExpression("attribute_not_exists(" + IDEMPOTENCY_KEY + ")")
                .build();
        Put putAudit = auditEntry(AuditEntityMapper.orderPlaced(order));
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().put(putOrder).build(),
                        TransactWriteItem.builder().put(putKey).build(),
                        TransactWriteItem.builder().put(putAudit).build())
                .build();
        return retrying(Mono.fromFuture(() -> client.transactWriteItems(request)))
                .onErrorMap(TransactionCanceledException.class, error -> conditionFailedAt(error, KEY_WRITE)
                        ? new DuplicateOrderException(idempotencyKey)
                        : error)
                .thenReturn(order);
    }

    @Override
    public Mono<Order> findById(String orderId) {
        Key key = Key.builder().partitionValue(orderId).build();
        return retrying(Mono.fromFuture(() -> orders.getItem(request -> request.key(key).consistentRead(true))))
                .map(OrderEntityMapper::toDomain);
    }

    @Override
    public Mono<Order> findByIdempotencyKey(String customerId, String idempotencyKey) {
        Key key = Key.builder().partitionValue(scopedKey(customerId, idempotencyKey)).build();
        return retrying(Mono.fromFuture(
                () -> idempotencyKeys.getItem(request -> request.key(key).consistentRead(true))))
                .flatMap(entity -> findById(entity.getOrderId()));
    }

    @Override
    public Flux<Order> findOverdue(OrderStatus status, Instant now) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.sortLessThanOrEqualTo(Key.builder()
                        .partitionValue(status.name())
                        .sortValue(now.getEpochSecond())
                        .build()))
                .limit(properties.overdueBatchSize())
                .build();
        return retrying(Mono.defer(() -> Mono.from(orders.index(OVERDUE_INDEX).query(request))))
                .flatMapIterable(Page::items)
                .map(OrderEntityMapper::toDomain);
    }

    @Override
    public Mono<Order> apply(OrderTransition transition) {
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().update(orderUpdate(transition)).build());
        if (transition.movesInventory()) {
            writes.add(TransactWriteItem.builder().update(inventoryUpdate(transition)).build());
        }
        writes.add(TransactWriteItem.builder()
                .put(auditEntry(AuditEntityMapper.orderTransition(transition)))
                .build());
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder().transactItems(writes).build();

        return retrying(Mono.fromFuture(() -> client.transactWriteItems(request)))
                .onErrorMap(TransactionCanceledException.class, error -> toBusinessError(error, transition))
                .thenReturn(transition.to());
    }

    @Override
    public Mono<Order> postpone(Order order, Instant newDeadline) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(orders.tableName())
                .key(Map.of(ORDER_ID, string(order.orderId())))
                .updateExpression("SET #expiresAt = :deadline")
                .conditionExpression("#status = :status")
                .expressionAttributeNames(Map.of("#status", STATUS, "#expiresAt", EXPIRES_AT))
                .expressionAttributeValues(Map.of(
                        ":status", string(order.status().name()),
                        ":deadline", epochSeconds(newDeadline)))
                .build();
        return retrying(Mono.fromFuture(() -> client.updateItem(request)))
                .onErrorMap(ConditionalCheckFailedException.class, _ -> new OrderConflictException(order.orderId()))
                .thenReturn(order.withDeadline(newDeadline));
    }

    private Update orderUpdate(OrderTransition transition) {
        Order to = transition.to();
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":from", string(transition.from().status().name()));
        values.put(":to", string(to.status().name()));
        values.put(":now", timestamp(transition.occurredAt()));
        values.put(":one", number(1));

        String expression = "SET #status = :to, #updatedAt = :now, #version = #version + :one";
        if (to.expiresAt() == null) {
            expression += " REMOVE #expiresAt";
        } else {
            expression += ", #expiresAt = :deadline";
            values.put(":deadline", epochSeconds(to.expiresAt()));
        }
        return Update.builder()
                .tableName(orders.tableName())
                .key(Map.of(ORDER_ID, string(to.orderId())))
                .updateExpression(expression)
                .conditionExpression("#status = :from")
                .expressionAttributeNames(Map.of(
                        "#status", STATUS,
                        "#updatedAt", UPDATED_AT,
                        "#version", OrderEntity.VERSION,
                        "#expiresAt", EXPIRES_AT))
                .expressionAttributeValues(values)
                .build();
    }

    private Update inventoryUpdate(OrderTransition transition) {
        return Update.builder()
                .tableName(events.tableName())
                .key(Map.of(EVENT_ID, string(transition.to().eventId())))
                .updateExpression("SET #source = #source - :qty, #target = #target + :qty, #version = #version + :one")
                .conditionExpression("#source >= :qty")
                .expressionAttributeNames(Map.of(
                        "#source", counterOf(transition.sourceTicketStatus()),
                        "#target", counterOf(transition.targetTicketStatus()),
                        "#version", EventEntity.VERSION))
                .expressionAttributeValues(Map.of(":qty", number(transition.quantity()), ":one", number(1)))
                .build();
    }

    private Put auditEntry(AuditEntity entry) {
        return Put.builder()
                .tableName(audit.tableName())
                .item(audit.tableSchema().itemToMap(entry, true))
                .build();
    }

    private Throwable toBusinessError(TransactionCanceledException error, OrderTransition transition) {
        if (conditionFailedAt(error, ORDER_WRITE)) {
            return new OrderConflictException(transition.to().orderId());
        }
        if (transition.movesInventory() && conditionFailedAt(error, INVENTORY_WRITE)) {
            return transition.sourceTicketStatus() == TicketStatus.AVAILABLE
                    ? new InsufficientTicketsException(transition.quantity())
                    : new IllegalStateException("Inventory invariant violated for event " + transition.to().eventId());
        }
        return error;
    }

    private static String scopedKey(String customerId, String idempotencyKey) {
        return customerId + "#" + idempotencyKey;
    }
}
