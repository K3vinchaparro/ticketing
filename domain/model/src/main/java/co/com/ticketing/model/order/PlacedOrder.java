package co.com.ticketing.model.order;

public record PlacedOrder(Order order, boolean replayed) {
}
