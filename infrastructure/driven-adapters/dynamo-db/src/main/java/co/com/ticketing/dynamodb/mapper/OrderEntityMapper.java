package co.com.ticketing.dynamodb.mapper;

import co.com.ticketing.dynamodb.entity.OrderEntity;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;

import java.time.Instant;

public final class OrderEntityMapper {

    private OrderEntityMapper() {
    }

    public static OrderEntity toEntity(Order order) {
        return OrderEntity.builder()
                .orderId(order.orderId())
                .eventId(order.eventId())
                .customerId(order.customerId())
                .quantity(order.quantity())
                .status(order.status().name())
                .expiresAt(order.expiresAt() == null ? null : order.expiresAt().getEpochSecond())
                .createdAt(order.createdAt())
                .updatedAt(order.updatedAt())
                .version(0L)
                .build();
    }

    public static Order toDomain(OrderEntity entity) {
        return new Order(
                entity.getOrderId(),
                entity.getEventId(),
                entity.getCustomerId(),
                entity.getQuantity(),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getExpiresAt() == null ? null : Instant.ofEpochSecond(entity.getExpiresAt()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
