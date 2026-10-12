package co.com.ticketing.api.handler;

import co.com.ticketing.api.dto.CreateOrderRequest;
import co.com.ticketing.api.dto.OrderResponse;
import co.com.ticketing.api.validation.RequestValidator;
import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.order.PlacedOrder;
import co.com.ticketing.usecase.order.ConfirmOrderUseCase;
import co.com.ticketing.usecase.order.GetOrderUseCase;
import co.com.ticketing.usecase.order.PlaceOrderUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class OrderHandler {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final String ORDER_ID = "orderId";

    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final ConfirmOrderUseCase confirmOrderUseCase;
    private final RequestValidator validator;
    private final MeterRegistry meterRegistry;

    public Mono<ServerResponse> place(ServerRequest request) {
        return idempotencyKey(request)
                .flatMap(key -> request.bodyToMono(CreateOrderRequest.class)
                        .switchIfEmpty(Mono.error(() -> new InvalidDataException("Request body is required")))
                        .flatMap(validator::validate)
                        .flatMap(body -> placeOrderUseCase.place(body.toCommand(key))))
                .flatMap(this::toResponse);
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return getOrderUseCase.getById(request.pathVariable(ORDER_ID))
                .flatMap(order -> ServerResponse.ok().bodyValue(OrderResponse.from(order)));
    }

    public Mono<ServerResponse> confirm(ServerRequest request) {
        return confirmOrderUseCase.confirm(request.pathVariable(ORDER_ID))
                .flatMap(order -> ServerResponse.ok().bodyValue(OrderResponse.from(order)));
    }

    private Mono<ServerResponse> toResponse(PlacedOrder placed) {
        OrderResponse body = OrderResponse.from(placed.order());
        URI location = URI.create("/api/v1/orders/" + body.orderId());
        if (placed.replayed()) {
            return ServerResponse.ok().location(location).bodyValue(body);
        }
        meterRegistry.counter("orders.placed").increment();
        return ServerResponse.accepted().location(location).bodyValue(body);
    }

    private static Mono<String> idempotencyKey(ServerRequest request) {
        String key = request.headers().firstHeader(IDEMPOTENCY_KEY_HEADER);
        return key != null && IDEMPOTENCY_KEY_FORMAT.matcher(key).matches()
                ? Mono.just(key)
                : Mono.error(new InvalidDataException(IDEMPOTENCY_KEY_HEADER
                        + " header is required: 8 to 64 letters, digits, '-' or '_'"));
    }
}
