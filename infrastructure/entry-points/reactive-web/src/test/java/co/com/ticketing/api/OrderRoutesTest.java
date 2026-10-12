package co.com.ticketing.api;

import co.com.ticketing.api.error.ErrorResponseMapper;
import co.com.ticketing.api.filter.AdminApiKeyFilter;
import co.com.ticketing.api.handler.EventHandler;
import co.com.ticketing.api.handler.OrderHandler;
import co.com.ticketing.model.exceptions.BusinessException;
import co.com.ticketing.model.exceptions.DuplicateOrderException;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import co.com.ticketing.model.exceptions.IdempotencyKeyReuseException;
import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.exceptions.PaymentDeclinedException;
import co.com.ticketing.model.exceptions.ReservationExpiredException;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.PlaceOrderCommand;
import co.com.ticketing.model.order.PlacedOrder;
import co.com.ticketing.usecase.order.ConfirmOrderUseCase;
import co.com.ticketing.usecase.order.GetOrderUseCase;
import co.com.ticketing.usecase.order.PlaceOrderUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.stream.Stream;

import static co.com.ticketing.api.ApiTestSupport.order;
import static co.com.ticketing.api.ApiTestSupport.properties;
import static co.com.ticketing.api.ApiTestSupport.validator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderRoutesTest {

    private static final String KEY = "key-12345678";
    private static final String VALID_ORDER = """
            {"eventId":"e1","customerId":"c1","quantity":2}""";

    @Mock
    private PlaceOrderUseCase placeOrderUseCase;
    @Mock
    private GetOrderUseCase getOrderUseCase;
    @Mock
    private ConfirmOrderUseCase confirmOrderUseCase;
    @Mock
    private EventHandler eventHandler;

    private SimpleMeterRegistry meterRegistry;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        OrderHandler handler = new OrderHandler(placeOrderUseCase, getOrderUseCase, confirmOrderUseCase, validator(),
                meterRegistry);
        client = WebTestClient.bindToRouterFunction(new RouterRest().routerFunction(eventHandler, handler,
                new AdminApiKeyFilter(properties(false, 1)), new ErrorResponseMapper())).build();
    }

    private WebTestClient.ResponseSpec placeOrder(String idempotencyKey, String body) {
        WebTestClient.RequestBodySpec request = client.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.bodyValue(body).exchange();
    }

    @Test
    void newOrderIsAcceptedAndPointsToItsStatus() {
        when(placeOrderUseCase.place(new PlaceOrderCommand("e1", "c1", 2, KEY)))
                .thenReturn(Mono.just(new PlacedOrder(order(OrderStatus.PENDING), false)));

        placeOrder(KEY, VALID_ORDER)
                .expectStatus().isAccepted()
                .expectHeader().location("/api/v1/orders/o1")
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("o1")
                .jsonPath("$.orderStatus").isEqualTo("PENDING")
                .jsonPath("$.ticketStatus").isEqualTo("AVAILABLE");

        assertThat(meterRegistry.counter("orders.placed").count()).isEqualTo(1);
    }

    @Test
    void repeatedRequestReturnsTheOriginalOrderWithOk() {
        when(placeOrderUseCase.place(any()))
                .thenReturn(Mono.just(new PlacedOrder(order(OrderStatus.RESERVED), true)));

        placeOrder(KEY, VALID_ORDER)
                .expectStatus().isOk()
                .expectHeader().location("/api/v1/orders/o1")
                .expectBody().jsonPath("$.orderStatus").isEqualTo("RESERVED");

        assertThat(meterRegistry.counter("orders.placed").count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has spaces in it", "bad$chars!!"})
    void idempotencyKeyMustHaveAValidFormat(String key) {
        placeOrder(key, VALID_ORDER)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("Idempotency-Key"));

        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void idempotencyKeyIsRequired() {
        placeOrder(null, VALID_ORDER).expectStatus().isBadRequest();

        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void invalidOrderIsRejected() {
        placeOrder(KEY, """
                {"eventId":"","customerId":"bad id!","quantity":0}""")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.eventId").exists()
                .jsonPath("$.errors.customerId").exists()
                .jsonPath("$.errors.quantity").isEqualTo("must be greater than or equal to 1");

        client.post().uri("/api/v1/orders")
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void orderShowsBothOrderAndTicketStatus() {
        when(getOrderUseCase.getById("o1")).thenReturn(Mono.just(order(OrderStatus.PENDING_CONFIRMATION)));

        client.get().uri("/api/v1/orders/o1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderStatus").isEqualTo("PENDING_CONFIRMATION")
                .jsonPath("$.ticketStatus").isEqualTo("PENDING_CONFIRMATION")
                .jsonPath("$.quantity").isEqualTo(2)
                .jsonPath("$.expiresAt").isEqualTo("2026-01-01T12:10:00Z");
    }

    @Test
    void confirmedOrderIsSold() {
        when(confirmOrderUseCase.confirm("o1")).thenReturn(Mono.just(order(OrderStatus.SOLD)));

        client.post().uri("/api/v1/orders/o1/confirm")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderStatus").isEqualTo("SOLD")
                .jsonPath("$.ticketStatus").isEqualTo("SOLD");
    }

    static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new OrderNotFoundException("o1"), 404),
                Arguments.of(new EventNotFoundException("e1"), 404),
                Arguments.of(new InvalidDataException("bad data"), 400),
                Arguments.of(new InvalidTransitionException(OrderStatus.PENDING, OrderStatus.SOLD), 409),
                Arguments.of(new OrderConflictException("o1"), 409),
                Arguments.of(new DuplicateOrderException("key"), 409),
                Arguments.of(new IdempotencyKeyReuseException("key"), 422),
                Arguments.of(new PaymentDeclinedException("o1"), 402),
                Arguments.of(new ReservationExpiredException("o1"), 410));
    }

    @ParameterizedTest
    @MethodSource("businessErrors")
    void eachBusinessErrorHasItsOwnHttpStatus(BusinessException error, int expectedStatus) {
        when(confirmOrderUseCase.confirm("o1")).thenReturn(Mono.error(error));

        client.post().uri("/api/v1/orders/o1/confirm")
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expectedStatus)
                .jsonPath("$.detail").isEqualTo(error.getMessage());
    }
}
