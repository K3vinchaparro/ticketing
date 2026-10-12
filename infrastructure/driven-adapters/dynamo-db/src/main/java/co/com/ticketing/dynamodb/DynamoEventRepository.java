package co.com.ticketing.dynamodb;

import co.com.ticketing.dynamodb.entity.AuditEntity;
import co.com.ticketing.dynamodb.entity.EventEntity;
import co.com.ticketing.dynamodb.mapper.AuditEntityMapper;
import co.com.ticketing.dynamodb.mapper.EventEntityMapper;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.gateways.EventRepository;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.ticket.TicketStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.Map;

import static co.com.ticketing.dynamodb.entity.EventEntity.EVENT_ID;
import static co.com.ticketing.dynamodb.entity.EventEntity.counterOf;
import static co.com.ticketing.dynamodb.helper.Attributes.number;
import static co.com.ticketing.dynamodb.helper.Attributes.string;
import static co.com.ticketing.dynamodb.helper.TransientErrors.conditionFailedAt;
import static co.com.ticketing.dynamodb.helper.TransientErrors.retrying;

@Repository
@RequiredArgsConstructor
public class DynamoEventRepository implements EventRepository {

    private static final int SCAN_PAGE_SIZE = 100;
    private static final Expression EVENT_DOES_NOT_EXIST = Expression.builder()
            .expression("attribute_not_exists(" + EVENT_ID + ")")
            .build();

    private final DynamoDbAsyncClient client;
    private final DynamoDbAsyncTable<EventEntity> events;
    private final DynamoDbAsyncTable<AuditEntity> audit;

    @Override
    public Mono<Event> save(Event event) {
        PutItemEnhancedRequest<EventEntity> request = PutItemEnhancedRequest.builder(EventEntity.class)
                .item(EventEntityMapper.toEntity(event))
                .conditionExpression(EVENT_DOES_NOT_EXIST)
                .build();
        return retrying(Mono.fromFuture(() -> events.putItem(request))).thenReturn(event);
    }

    @Override
    public Mono<Event> findById(String eventId) {
        Key key = Key.builder().partitionValue(eventId).build();
        return retrying(Mono.fromFuture(() -> events.getItem(request -> request.key(key).consistentRead(true))))
                .map(EventEntityMapper::toDomain);
    }

    @Override
    public Flux<Event> findAll() {
        return scanPage(null)
                .expand(page -> page.lastEvaluatedKey() == null || page.lastEvaluatedKey().isEmpty()
                        ? Mono.empty()
                        : scanPage(page.lastEvaluatedKey()))
                .flatMapIterable(Page::items)
                .map(EventEntityMapper::toDomain);
    }

    @Override
    public Mono<Event> assignComplimentary(String eventId, int quantity, Instant occurredAt) {
        Update moveTickets = Update.builder()
                .tableName(events.tableName())
                .key(Map.of(EVENT_ID, string(eventId)))
                .updateExpression("SET #available = #available - :qty, #complimentary = #complimentary + :qty, "
                        + "#version = #version + :one")
                .conditionExpression("#available >= :qty")
                .expressionAttributeNames(Map.of(
                        "#available", counterOf(TicketStatus.AVAILABLE),
                        "#complimentary", counterOf(TicketStatus.COMPLIMENTARY),
                        "#version", EventEntity.VERSION))
                .expressionAttributeValues(Map.of(":qty", number(quantity), ":one", number(1)))
                .build();
        AuditEntity entry = AuditEntityMapper.complimentaryAssigned(eventId, quantity, occurredAt);
        Put auditEntry = Put.builder()
                .tableName(audit.tableName())
                .item(audit.tableSchema().itemToMap(entry, true))
                .build();
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().update(moveTickets).build(),
                        TransactWriteItem.builder().put(auditEntry).build())
                .build();
        return retrying(Mono.fromFuture(() -> client.transactWriteItems(request)))
                .onErrorMap(TransactionCanceledException.class, error -> conditionFailedAt(error, 0)
                        ? new InsufficientTicketsException(quantity)
                        : error)
                .then(findById(eventId));
    }

    private Mono<Page<EventEntity>> scanPage(Map<String, AttributeValue> startKey) {
        ScanEnhancedRequest request = ScanEnhancedRequest.builder()
                .limit(SCAN_PAGE_SIZE)
                .exclusiveStartKey(startKey)
                .build();
        return retrying(Mono.defer(() -> Mono.from(events.scan(request))));
    }
}
