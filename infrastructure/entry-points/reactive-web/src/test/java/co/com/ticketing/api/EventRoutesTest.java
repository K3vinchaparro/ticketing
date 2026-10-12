package co.com.ticketing.api;

import co.com.ticketing.api.error.ErrorResponseMapper;
import co.com.ticketing.api.filter.AdminApiKeyFilter;
import co.com.ticketing.api.handler.EventHandler;
import co.com.ticketing.api.handler.OrderHandler;
import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.usecase.event.AssignComplimentaryUseCase;
import co.com.ticketing.usecase.event.CreateEventUseCase;
import co.com.ticketing.usecase.event.GetAvailabilityUseCase;
import co.com.ticketing.usecase.event.GetEventUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static co.com.ticketing.api.ApiTestSupport.ADMIN_KEY;
import static co.com.ticketing.api.ApiTestSupport.event;
import static co.com.ticketing.api.ApiTestSupport.properties;
import static co.com.ticketing.api.ApiTestSupport.validator;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventRoutesTest {

    private static final String VALID_EVENT = """
            {"name":"Rock Fest","date":"2099-01-01T20:00:00Z","venue":"Arena","capacity":100}""";

    @Mock
    private CreateEventUseCase createEventUseCase;
    @Mock
    private GetEventUseCase getEventUseCase;
    @Mock
    private GetAvailabilityUseCase getAvailabilityUseCase;
    @Mock
    private AssignComplimentaryUseCase assignComplimentaryUseCase;
    @Mock
    private OrderHandler orderHandler;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        EventHandler handler = new EventHandler(createEventUseCase, getEventUseCase, getAvailabilityUseCase,
                assignComplimentaryUseCase, validator(), properties(false, 1));
        client = WebTestClient.bindToRouterFunction(new RouterRest().routerFunction(handler, orderHandler,
                new AdminApiKeyFilter(properties(false, 1)), new ErrorResponseMapper())).build();
    }

    @Test
    void createsAnEvent() {
        when(createEventUseCase.create("Rock Fest", Instant.parse("2099-01-01T20:00:00Z"), "Arena", 100))
                .thenReturn(Mono.just(event(Inventory.ofCapacity(100))));

        client.post().uri("/api/v1/events")
                .header("X-Api-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_EVENT)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/api/v1/events/e1")
                .expectBody()
                .jsonPath("$.eventId").isEqualTo("e1")
                .jsonPath("$.capacity").isEqualTo(100)
                .jsonPath("$.available").isEqualTo(100)
                .jsonPath("$.date").isEqualTo("2027-01-01T20:00:00Z");
    }

    @Test
    void creatingAnEventRequiresTheAdminKey() {
        client.post().uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_EVENT)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);

        client.post().uri("/api/v1/events")
                .header("X-Api-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_EVENT)
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void invalidEventIsRejectedWithOneMessagePerField() {
        client.post().uri("/api/v1/events")
                .header("X-Api-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"","date":"2000-01-01T00:00:00Z","venue":"Arena","capacity":0}""")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.instance").isEqualTo("/api/v1/events")
                .jsonPath("$.errors.name").isEqualTo("must not be blank")
                .jsonPath("$.errors.date").isEqualTo("must be a future date")
                .jsonPath("$.errors.capacity").isEqualTo("must be greater than or equal to 1")
                .jsonPath("$.errors.venue").doesNotExist();

        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void emptyOrMalformedBodyIsABadRequest() {
        client.post().uri("/api/v1/events")
                .header("X-Api-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();

        client.post().uri("/api/v1/events")
                .header("X-Api-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("The request could not be processed");
    }

    @Test
    void listsEvents() {
        when(getEventUseCase.getAll())
                .thenReturn(Flux.just(event(Inventory.ofCapacity(100)), event(Inventory.ofCapacity(50))));

        client.get().uri("/api/v1/events")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[1].capacity").isEqualTo(50);
    }

    @Test
    void getsAnEvent() {
        when(getEventUseCase.getById("e1")).thenReturn(Mono.just(event(Inventory.ofCapacity(100))));

        client.get().uri("/api/v1/events/e1")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Rock Fest");
    }

    @Test
    void missingEventIsNotFound() {
        when(getEventUseCase.getById("missing")).thenReturn(Mono.error(new EventNotFoundException("missing")));

        client.get().uri("/api/v1/events/missing")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Event not found: missing")
                .jsonPath("$.instance").isEqualTo("/api/v1/events/missing");
    }

    @Test
    void availabilityShowsEveryCounter() {
        when(getAvailabilityUseCase.current("e1")).thenReturn(Mono.just(event(new Inventory(90, 4, 3, 2, 1))));

        client.get().uri("/api/v1/events/e1/availability")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.capacity").isEqualTo(100)
                .jsonPath("$.available").isEqualTo(90)
                .jsonPath("$.reserved").isEqualTo(4)
                .jsonPath("$.pendingConfirmation").isEqualTo(3)
                .jsonPath("$.sold").isEqualTo(2)
                .jsonPath("$.complimentary").isEqualTo(1);
    }

    @Test
    void availabilityStreamPushesChangesAsServerSentEvents() {
        when(getAvailabilityUseCase.current("e1")).thenReturn(Mono.just(event(Inventory.ofCapacity(100))));
        when(getAvailabilityUseCase.stream("e1", Duration.ofMillis(50))).thenReturn(Flux.just(
                event(Inventory.ofCapacity(100)), event(new Inventory(98, 2, 0, 0, 0))));

        Flux<String> stream = client.get().uri("/api/v1/events/e1/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(stream)
                .expectNextMatches(data -> data.contains("\"available\":100"))
                .expectNextMatches(data -> data.contains("\"available\":98"))
                .verifyComplete();
    }

    @Test
    void availabilityStreamOfAMissingEventIsNotFound() {
        when(getAvailabilityUseCase.current("missing")).thenReturn(Mono.error(new EventNotFoundException("missing")));

        client.get().uri("/api/v1/events/missing/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void assignsComplimentaryTickets() {
        when(assignComplimentaryUseCase.assign("e1", 5)).thenReturn(Mono.just(event(new Inventory(95, 0, 0, 0, 5))));

        client.post().uri("/api/v1/events/e1/complimentary")
                .header("X-Api-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":5}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.complimentary").isEqualTo(5)
                .jsonPath("$.available").isEqualTo(95);
    }

    @Test
    void complimentaryBeyondTheAvailableTicketsIsAConflict() {
        when(assignComplimentaryUseCase.assign("e1", 5)).thenReturn(Mono.error(new InsufficientTicketsException(5)));

        client.post().uri("/api/v1/events/e1/complimentary")
                .header("X-Api-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":5}")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void complimentaryRequiresAdminKeyAndAValidBody() {
        client.post().uri("/api/v1/events/e1/complimentary")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":5}")
                .exchange()
                .expectStatus().isUnauthorized();

        client.post().uri("/api/v1/events/e1/complimentary")
                .header("X-Api-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":0}")
                .exchange()
                .expectStatus().isBadRequest();

        client.post().uri("/api/v1/events/e1/complimentary")
                .header("X-Api-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(assignComplimentaryUseCase);
    }

    @Test
    void unexpectedErrorDoesNotLeakInternalDetails() {
        when(getEventUseCase.getById(any())).thenReturn(Mono.error(new IllegalStateException("table events is gone")));

        client.get().uri("/api/v1/events/e1")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Unexpected error")
                .jsonPath("$.status").isEqualTo(500);
    }

    @Test
    void unknownRouteIsNotFound() {
        client.get().uri("/api/v1/unknown").exchange().expectStatus().isNotFound();

        verifyNoInteractions(getEventUseCase);
    }
}
