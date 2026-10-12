package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.dynamodb.entity.AuditEntity;
import co.com.ticketing.dynamodb.entity.EventEntity;
import co.com.ticketing.dynamodb.entity.OrderEntity;
import co.com.ticketing.model.event.Event;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.TransitionReason;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityMappersTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final TableSchema<EventEntity> EVENT_SCHEMA = TableSchema.fromBean(EventEntity.class);
    private static final TableSchema<OrderEntity> ORDER_SCHEMA = TableSchema.fromBean(OrderEntity.class);
    private static final TableSchema<AuditEntity> AUDIT_SCHEMA = TableSchema.fromBean(AuditEntity.class);

    @Test
    void eventSurvivesARoundTripThroughTheTable() {
        Event event = new Event("e1", "Rock Fest", NOW.plus(Duration.ofDays(1)), "Arena",
                new Inventory(5, 4, 3, 2, 1), NOW);

        Map<String, AttributeValue> item = EVENT_SCHEMA.itemToMap(EventEntityMapper.toEntity(event), true);

        assertThat(EventEntityMapper.toDomain(EVENT_SCHEMA.mapToItem(item))).isEqualTo(event);
        assertThat(item.get("capacity").n()).isEqualTo("15");
        assertThat(item.get("available").n()).isEqualTo("5");
        assertThat(item.get("pendingConfirmation").n()).isEqualTo("3");
        assertThat(item.get("version").n()).isEqualTo("0");
        assertThat(item.get("createdAt").s()).isEqualTo(NOW.toString());
    }

    @Test
    void eventTableIsKeyedByEventId() {
        assertThat(EVENT_SCHEMA.tableMetadata().primaryPartitionKey()).isEqualTo("eventId");
    }

    @Test
    void orderSurvivesARoundTripThroughTheTable() {
        Order order = new Order("o1", "e1", "c1", 2, OrderStatus.RESERVED, NOW.plusSeconds(600), NOW, NOW);

        Map<String, AttributeValue> item = ORDER_SCHEMA.itemToMap(OrderEntityMapper.toEntity(order), true);

        assertThat(OrderEntityMapper.toDomain(ORDER_SCHEMA.mapToItem(item))).isEqualTo(order);
        assertThat(item.get("status").s()).isEqualTo("RESERVED");
        assertThat(item.get("expiresAt").n()).isEqualTo(Long.toString(NOW.plusSeconds(600).getEpochSecond()));
    }

    @Test
    void orderWithoutDeadlineHasNoExpiresAtAttribute() {
        Order sold = new Order("o1", "e1", "c1", 2, OrderStatus.SOLD, null, NOW, NOW);

        Map<String, AttributeValue> item = ORDER_SCHEMA.itemToMap(OrderEntityMapper.toEntity(sold), true);

        assertThat(item).doesNotContainKey("expiresAt");
        assertThat(OrderEntityMapper.toDomain(ORDER_SCHEMA.mapToItem(item))).isEqualTo(sold);
    }

    @Test
    void orderTableDeclaresTheOverdueIndex() {
        assertThat(ORDER_SCHEMA.tableMetadata().primaryPartitionKey()).isEqualTo("orderId");
        assertThat(ORDER_SCHEMA.tableMetadata().indexPartitionKey(OrderEntity.OVERDUE_INDEX)).isEqualTo("status");
        assertThat(ORDER_SCHEMA.tableMetadata().indexSortKey(OrderEntity.OVERDUE_INDEX)).contains("expiresAt");
    }

    @Test
    void auditEntryOfANewOrder() {
        Order order = Order.pending("o1", "e1", "c1", 2, NOW, NOW.plusSeconds(120));

        AuditEntity entry = AuditEntityMapper.orderPlaced(order);

        assertThat(entry.getEntityId()).isEqualTo("o1");
        assertThat(entry.getOccurredAt()).isEqualTo(NOW + "#PENDING");
        assertThat(entry.getEntityType()).isEqualTo("ORDER");
        assertThat(entry.getFromOrderStatus()).isEqualTo("NONE");
        assertThat(entry.getToOrderStatus()).isEqualTo("PENDING");
        assertThat(entry.getReason()).isEqualTo("ORDER_PLACED");
        assertThat(entry.getQuantity()).isEqualTo(2);
    }

    @Test
    void auditEntryOfATransitionRecordsOrderAndTicketStates() {
        Order pending = Order.pending("o1", "e1", "c1", 2, NOW, NOW.plusSeconds(120));
        Instant later = NOW.plusSeconds(3);
        OrderTransition transition = OrderTransition.of(pending, OrderStatus.RESERVED, later,
                later.plusSeconds(600), TransitionReason.TICKETS_RESERVED);

        AuditEntity entry = AuditEntityMapper.orderTransition(transition);

        assertThat(entry.getEntityId()).isEqualTo("o1");
        assertThat(entry.getOccurredAt()).isEqualTo(later + "#RESERVED");
        assertThat(entry.getEventId()).isEqualTo("e1");
        assertThat(entry.getFromOrderStatus()).isEqualTo("PENDING");
        assertThat(entry.getToOrderStatus()).isEqualTo("RESERVED");
        assertThat(entry.getFromTicketStatus()).isEqualTo("AVAILABLE");
        assertThat(entry.getToTicketStatus()).isEqualTo("RESERVED");
        assertThat(entry.getReason()).isEqualTo("TICKETS_RESERVED");
    }

    @Test
    void auditEntryOfComplimentaryTicketsBelongsToTheEvent() {
        AuditEntity first = AuditEntityMapper.complimentaryAssigned("e1", 3, NOW);
        AuditEntity second = AuditEntityMapper.complimentaryAssigned("e1", 3, NOW);

        assertThat(first.getEntityId()).isEqualTo("e1");
        assertThat(first.getEntityType()).isEqualTo("EVENT");
        assertThat(first.getFromTicketStatus()).isEqualTo("AVAILABLE");
        assertThat(first.getToTicketStatus()).isEqualTo("COMPLIMENTARY");
        assertThat(first.getReason()).isEqualTo("COMPLIMENTARY_ASSIGNED");
        assertThat(first.getOccurredAt()).isNotEqualTo(second.getOccurredAt());
    }

    @Test
    void auditItemIsKeyedByEntityAndTimeAndOmitsMissingStates() {
        Map<String, AttributeValue> item = AUDIT_SCHEMA.itemToMap(
                AuditEntityMapper.complimentaryAssigned("e1", 3, NOW), true);

        assertThat(AUDIT_SCHEMA.tableMetadata().primaryPartitionKey()).isEqualTo("entityId");
        assertThat(AUDIT_SCHEMA.tableMetadata().primarySortKey()).contains("occurredAt");
        assertThat(item).doesNotContainKeys("fromOrderStatus", "toOrderStatus");
        assertThat(item.get("quantity").n()).isEqualTo("3");
    }
}
