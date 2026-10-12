package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.dynamodb.entity.AuditEntity;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderTransition;
import co.com.ticketing.model.order.TransitionReason;
import co.com.ticketing.model.ticket.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public final class AuditEntityMapper {

    private static final String ORDER = "ORDER";
    private static final String EVENT = "EVENT";
    private static final String NO_STATUS = "NONE";

    private AuditEntityMapper() {
    }

    public static AuditEntity orderPlaced(Order order) {
        return AuditEntity.builder()
                .entityId(order.orderId())
                .occurredAt(sortKey(order.createdAt(), order.status().name()))
                .entityType(ORDER)
                .eventId(order.eventId())
                .quantity(order.quantity())
                .reason(TransitionReason.ORDER_PLACED.name())
                .fromOrderStatus(NO_STATUS)
                .toOrderStatus(order.status().name())
                .fromTicketStatus(order.ticketStatus().name())
                .toTicketStatus(order.ticketStatus().name())
                .build();
    }

    public static AuditEntity orderTransition(OrderTransition transition) {
        Order to = transition.to();
        return AuditEntity.builder()
                .entityId(to.orderId())
                .occurredAt(sortKey(transition.occurredAt(), to.status().name()))
                .entityType(ORDER)
                .eventId(to.eventId())
                .quantity(to.quantity())
                .reason(transition.reason().name())
                .fromOrderStatus(transition.from().status().name())
                .toOrderStatus(to.status().name())
                .fromTicketStatus(transition.sourceTicketStatus().name())
                .toTicketStatus(transition.targetTicketStatus().name())
                .build();
    }

    public static AuditEntity complimentaryAssigned(String eventId, int quantity, Instant occurredAt) {
        return AuditEntity.builder()
                .entityId(eventId)
                .occurredAt(sortKey(occurredAt, UUID.randomUUID().toString()))
                .entityType(EVENT)
                .eventId(eventId)
                .quantity(quantity)
                .reason(TransitionReason.COMPLIMENTARY_ASSIGNED.name())
                .fromTicketStatus(TicketStatus.AVAILABLE.name())
                .toTicketStatus(TicketStatus.COMPLIMENTARY.name())
                .build();
    }

    private static String sortKey(Instant occurredAt, String suffix) {
        return occurredAt + "#" + suffix;
    }
}
