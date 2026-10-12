package co.com.ticketing.api.handler;

import co.com.ticketing.api.config.ApiProperties;
import co.com.ticketing.api.dto.AvailabilityResponse;
import co.com.ticketing.api.dto.ComplimentaryRequest;
import co.com.ticketing.api.dto.CreateEventRequest;
import co.com.ticketing.api.dto.EventResponse;
import co.com.ticketing.api.validation.RequestValidator;
import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.usecase.event.AssignComplimentaryUseCase;
import co.com.ticketing.usecase.event.CreateEventUseCase;
import co.com.ticketing.usecase.event.GetAvailabilityUseCase;
import co.com.ticketing.usecase.event.GetEventUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class EventHandler {

    private static final String EVENT_ID = "eventId";

    private final CreateEventUseCase createEventUseCase;
    private final GetEventUseCase getEventUseCase;
    private final GetAvailabilityUseCase getAvailabilityUseCase;
    private final AssignComplimentaryUseCase assignComplimentaryUseCase;
    private final RequestValidator validator;
    private final ApiProperties properties;

    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(CreateEventRequest.class)
                .switchIfEmpty(Mono.error(() -> new InvalidDataException("Request body is required")))
                .flatMap(validator::validate)
                .flatMap(body -> createEventUseCase.create(body.name(), body.date(), body.venue(), body.capacity()))
                .flatMap(event -> ServerResponse.created(URI.create("/api/v1/events/" + event.eventId()))
                        .bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        Flux<EventResponse> events = getEventUseCase.getAll().map(EventResponse::from);
        return ServerResponse.ok().body(events, EventResponse.class);
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return getEventUseCase.getById(request.pathVariable(EVENT_ID))
                .flatMap(event -> ServerResponse.ok().bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> availability(ServerRequest request) {
        return getAvailabilityUseCase.current(request.pathVariable(EVENT_ID))
                .flatMap(event -> ServerResponse.ok().bodyValue(AvailabilityResponse.from(event)));
    }

    public Mono<ServerResponse> availabilityStream(ServerRequest request) {
        String eventId = request.pathVariable(EVENT_ID);
        return getAvailabilityUseCase.current(eventId)
                .flatMap(_ -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(getAvailabilityUseCase.stream(eventId, properties.availabilityPollInterval())
                                .map(AvailabilityResponse::from), AvailabilityResponse.class));
    }

    public Mono<ServerResponse> assignComplimentary(ServerRequest request) {
        return request.bodyToMono(ComplimentaryRequest.class)
                .switchIfEmpty(Mono.error(() -> new InvalidDataException("Request body is required")))
                .flatMap(validator::validate)
                .flatMap(body -> assignComplimentaryUseCase.assign(request.pathVariable(EVENT_ID), body.quantity()))
                .flatMap(event -> ServerResponse.ok().bodyValue(AvailabilityResponse.from(event)));
    }
}
