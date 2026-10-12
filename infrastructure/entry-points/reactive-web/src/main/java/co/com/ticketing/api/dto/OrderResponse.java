package co.com.ticketing.api.dto;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.ticket.TicketStatus;

import java.time.Instant;

public record OrderResponse(String orderId, String eventId, String customerId, int quantity, OrderStatus orderStatus,
                            TicketStatus ticketStatus, Instant expiresAt, Instant createdAt, Instant updatedAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.orderId(), order.eventId(), order.customerId(), order.quantity(),
                order.status(), order.ticketStatus(), order.expiresAt(), order.createdAt(), order.updatedAt());
    }
}
