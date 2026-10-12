package co.com.ticketing.model.order;

public record PlaceOrderCommand(String eventId, String customerId, int quantity, String idempotencyKey) {

    public boolean matches(Order order) {
        return order.eventId().equals(eventId)
                && order.customerId().equals(customerId)
                && order.quantity() == quantity;
    }
}
