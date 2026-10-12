package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.ticketing.dynamodb.config.DynamoDbTablesConfig;
import co.com.ticketing.dynamodb.entity.AuditEntity;
import co.com.ticketing.dynamodb.entity.EventEntity;
import co.com.ticketing.dynamodb.entity.OrderEntity;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.exceptions.DuplicateOrderException;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.TransitionReason;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DYNAMODB_IT_ENDPOINT", matches = ".+")
class DynamoDbIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String SUFFIX = "_it_" + UUID.randomUUID().toString().substring(0, 8);
    private static final DynamoDbProperties PROPERTIES = new DynamoDbProperties(
            "events" + SUFFIX, "orders" + SUFFIX, "idempotency_keys" + SUFFIX, "ticket_audit" + SUFFIX,
            Duration.ofHours(24), 100);

    private static DynamoDbAsyncClient client;
    private static DynamoEventRepository events;
    private static DynamoOrderRepository orders;

    @BeforeAll
    static void createTables() {
        client = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create(System.getenv("DYNAMODB_IT_ENDPOINT")))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        DynamoDbTablesConfig tables = new DynamoDbTablesConfig();
        DynamoDbEnhancedAsyncClient enhanced = tables.dynamoDbEnhancedAsyncClient(client);
        DynamoDbAsyncTable<EventEntity> eventsTable = tables.eventsTable(enhanced, PROPERTIES);
        DynamoDbAsyncTable<AuditEntity> auditTable = tables.auditTable(enhanced, PROPERTIES);
        events = new DynamoEventRepository(client, eventsTable, auditTable);
        orders = new DynamoOrderRepository(client, tables.ordersTable(enhanced, PROPERTIES),
                tables.idempotencyKeysTable(enhanced, PROPERTIES), eventsTable, auditTable, PROPERTIES);

        createTable(PROPERTIES.eventsTable(), "eventId", null);
        createTable(PROPERTIES.idempotencyTable(), "idempotencyKey", null);
        createTable(PROPERTIES.auditTable(), "entityId", "occurredAt");
        client.createTable(table -> table
                .tableName(PROPERTIES.ordersTable())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        attribute("orderId", ScalarAttributeType.S),
                        attribute("status", ScalarAttributeType.S),
                        attribute("expiresAt", ScalarAttributeType.N))
                .keySchema(key("orderId", KeyType.HASH))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(OrderEntity.OVERDUE_INDEX)
                        .keySchema(key("status", KeyType.HASH), key("expiresAt", KeyType.RANGE))
                        .projection(projection -> projection.projectionType(ProjectionType.ALL))
                        .build())).join();
    }

    @AfterAll
    static void dropTables() {
        List.of(PROPERTIES.eventsTable(), PROPERTIES.ordersTable(), PROPERTIES.idempotencyTable(),
                        PROPERTIES.auditTable())
                .forEach(table -> client.deleteTable(request -> request.tableName(table)).join());
        client.close();
    }

    @Test
    void hundredConcurrentReservationsForTenTicketsReserveExactlyTen() {
        Event event = newEvent(10);
        List<Order> pending = Flux.range(0, 100)
                .concatMap(i -> orders.create(newOrder(event.eventId(), 1), "key-" + i))
                .collectList()
                .block(TIMEOUT);

        List<String> outcomes = Flux.fromIterable(pending)
                .parallel(16)
                .runOn(Schedulers.boundedElastic())
                .flatMap(order -> orders.apply(reservation(order))
                        .map(_ -> "RESERVED")
                        .onErrorResume(InsufficientTicketsException.class, _ -> Mono.just("SOLD_OUT")))
                .sequential()
                .collectList()
                .block(TIMEOUT);

        assertThat(outcomes).filteredOn("RESERVED"::equals).hasSize(10);
        assertThat(outcomes).filteredOn("SOLD_OUT"::equals).hasSize(90);
        assertThat(events.findById(event.eventId()).block(TIMEOUT).inventory())
                .isEqualTo(new Inventory(0, 10, 0, 0, 0));
    }

    @Test
    void sameIdempotencyKeyUnderConcurrencyCreatesASingleOrder() {
        Event event = newEvent(10);
        String key = "key-" + UUID.randomUUID();

        List<String> outcomes = Flux.range(0, 30)
                .parallel(16)
                .runOn(Schedulers.boundedElastic())
                .flatMap(_ -> orders.create(newOrder(event.eventId(), 1), key)
                        .map(_ -> "CREATED")
                        .onErrorResume(DuplicateOrderException.class, _ -> Mono.just("DUPLICATE")))
                .sequential()
                .collectList()
                .block(TIMEOUT);

        assertThat(outcomes).filteredOn("CREATED"::equals).hasSize(1);
        assertThat(orders.findByIdempotencyKey("c1", key).block(TIMEOUT)).isNotNull();
    }

    @Test
    void confirmAndReleaseRaceHasASingleWinner() {
        Event event = newEvent(5);
        Order pending = orders.create(newOrder(event.eventId(), 2), "key-" + UUID.randomUUID()).block(TIMEOUT);
        Order reserved = orders.apply(reservation(pending)).block(TIMEOUT);
        Instant now = Instant.now();

        Mono<String> confirm = orders.apply(OrderTransition.of(reserved, OrderStatus.PENDING_CONFIRMATION, now,
                        now.plusSeconds(120), TransitionReason.PAYMENT_STARTED))
                .map(_ -> "WON")
                .onErrorResume(OrderConflictException.class, _ -> Mono.just("LOST"));
        Mono<String> release = orders.apply(OrderTransition.of(reserved, OrderStatus.RELEASED, now, null,
                        TransitionReason.RESERVATION_EXPIRED))
                .map(_ -> "WON")
                .onErrorResume(OrderConflictException.class, _ -> Mono.just("LOST"));

        List<String> outcomes = Flux.merge(
                        confirm.subscribeOn(Schedulers.boundedElastic()),
                        release.subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .block(TIMEOUT);

        assertThat(outcomes).containsExactlyInAnyOrder("WON", "LOST");
        Inventory inventory = events.findById(event.eventId()).block(TIMEOUT).inventory();
        assertThat(inventory.capacity()).isEqualTo(5);
        assertThat(inventory.reserved()).isZero();
    }

    @Test
    void overdueOrdersAreFoundThroughTheIndexAndLeaveItWhenFinal() {
        Event event = newEvent(5);
        Instant past = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        Order pending = orders.create(newOrder(event.eventId(), 1), "key-" + UUID.randomUUID()).block(TIMEOUT);
        Order reserved = orders.apply(OrderTransition.of(pending, OrderStatus.RESERVED, past, past,
                TransitionReason.TICKETS_RESERVED)).block(TIMEOUT);

        assertThat(orders.findOverdue(OrderStatus.RESERVED, Instant.now()).collectList().block(TIMEOUT))
                .extracting(Order::orderId).contains(reserved.orderId());

        orders.apply(OrderTransition.of(reserved, OrderStatus.RELEASED, Instant.now(), null,
                TransitionReason.RESERVATION_EXPIRED)).block(TIMEOUT);

        assertThat(orders.findOverdue(OrderStatus.RESERVED, Instant.now()).collectList().block(TIMEOUT))
                .extracting(Order::orderId).doesNotContain(reserved.orderId());
        assertThat(orders.findById(reserved.orderId()).block(TIMEOUT).expiresAt()).isNull();
    }

    @Test
    void complimentaryTicketsCannotExceedTheAvailableOnes() {
        Event event = newEvent(3);

        Event updated = events.assignComplimentary(event.eventId(), 2, Instant.now()).block(TIMEOUT);

        assertThat(updated.inventory()).isEqualTo(new Inventory(1, 0, 0, 0, 2));
        assertThat(events.assignComplimentary(event.eventId(), 2, Instant.now())
                .map(_ -> "ASSIGNED")
                .onErrorResume(InsufficientTicketsException.class, _ -> Mono.just("REJECTED"))
                .block(TIMEOUT)).isEqualTo("REJECTED");
    }

    private static Event newEvent(int capacity) {
        Instant now = Instant.now();
        Event event = Event.create(UUID.randomUUID().toString(), "Rock Fest", now.plus(Duration.ofDays(1)), "Arena",
                capacity, now);
        return events.save(event).block(TIMEOUT);
    }

    private static Order newOrder(String eventId, int quantity) {
        Instant now = Instant.now();
        return Order.pending(UUID.randomUUID().toString(), eventId, "c1", quantity, now, now.plusSeconds(120));
    }

    private static OrderTransition reservation(Order pending) {
        Instant now = Instant.now();
        return OrderTransition.of(pending, OrderStatus.RESERVED, now, now.plusSeconds(600),
                TransitionReason.TICKETS_RESERVED);
    }

    private static void createTable(String name, String partitionKey, String sortKey) {
        client.createTable(table -> {
            table.tableName(name).billingMode(BillingMode.PAY_PER_REQUEST);
            if (sortKey == null) {
                table.attributeDefinitions(attribute(partitionKey, ScalarAttributeType.S))
                        .keySchema(key(partitionKey, KeyType.HASH));
            } else {
                table.attributeDefinitions(attribute(partitionKey, ScalarAttributeType.S),
                                attribute(sortKey, ScalarAttributeType.S))
                        .keySchema(key(partitionKey, KeyType.HASH), key(sortKey, KeyType.RANGE));
            }
        }).join();
    }

    private static AttributeDefinition attribute(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
