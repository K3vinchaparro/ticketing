package co.com.ticketing.api.dto;

import co.com.ticketing.model.order.PlaceOrderCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.@-]{1,64}") String customerId,
        @NotNull @Min(1) @Max(100) Integer quantity) {

    public PlaceOrderCommand toCommand(String idempotencyKey) {
        return new PlaceOrderCommand(eventId, customerId, quantity, idempotencyKey);
    }
}
