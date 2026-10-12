package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.ticketing.dynamodb.config.DynamoDbTablesConfig;
import co.com.ticketing.dynamodb.entity.EventEntity;
import co.com.ticketing.dynamodb.entity.OrderEntity;
import co.com.ticketing.dynamodb.mapper.EventEntityMapper;
import co.com.ticketing.dynamodb.mapper.OrderEntityMapper;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.paginators.QueryPublisher;
import software.amazon.awssdk.services.dynamodb.paginators.ScanPublisher;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

final class TestData {

    static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    static final DynamoDbProperties PROPERTIES = new DynamoDbProperties(
            "events", "orders", "idempotency_keys", "ticket_audit", Duration.ofHours(24), 100);
    private static final DynamoDbTablesConfig TABLES = new DynamoDbTablesConfig();
    private static final TableSchema<EventEntity> EVENT_SCHEMA = TableSchema.fromBean(EventEntity.class);
    private static final TableSchema<OrderEntity> ORDER_SCHEMA = TableSchema.fromBean(OrderEntity.class);

    private TestData() {
    }

    static DynamoEventRepository eventRepository(DynamoDbAsyncClient client) {
        DynamoDbEnhancedAsyncClient enhanced = enhanced(client);
        return new DynamoEventRepository(client, TABLES.eventsTable(enhanced, PROPERTIES),
                TABLES.auditTable(enhanced, PROPERTIES));
    }

    static DynamoOrderRepository orderRepository(DynamoDbAsyncClient client) {
        DynamoDbEnhancedAsyncClient enhanced = enhanced(client);
        return new DynamoOrderRepository(client, TABLES.ordersTable(enhanced, PROPERTIES),
                TABLES.idempotencyKeysTable(enhanced, PROPERTIES), TABLES.eventsTable(enhanced, PROPERTIES),
                TABLES.auditTable(enhanced, PROPERTIES), PROPERTIES);
    }

    static Map<String, AttributeValue> item(Event event) {
        return EVENT_SCHEMA.itemToMap(EventEntityMapper.toEntity(event), true);
    }

    static Map<String, AttributeValue> item(Order order) {
        return ORDER_SCHEMA.itemToMap(OrderEntityMapper.toEntity(order), true);
    }

    private static DynamoDbEnhancedAsyncClient enhanced(DynamoDbAsyncClient client) {
        lenient().when(client.scanPaginator(any(ScanRequest.class)))
                .thenAnswer(call -> new ScanPublisher(client, call.getArgument(0)));
        lenient().when(client.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(call -> new QueryPublisher(client, call.getArgument(0)));
        return TABLES.dynamoDbEnhancedAsyncClient(client);
    }

    static Event event(int capacity) {
        return Event.create("e1", "Rock Fest", NOW.plus(Duration.ofDays(30)), "Arena", capacity, NOW);
    }

    static Order order(OrderStatus status) {
        Instant deadline = status.isFinal() ? null : NOW.plusSeconds(300);
        return new Order("o1", "e1", "c1", 2, status, deadline, NOW, NOW);
    }

    static TransactionCanceledException cancelled(String... reasonCodes) {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(Arrays.stream(reasonCodes)
                        .map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }

    static <T> CompletableFuture<T> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
