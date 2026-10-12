package co.com.ticketing.api.error;

import co.com.ticketing.api.validation.RequestValidationException;
import co.com.ticketing.model.exceptions.BusinessException;
import co.com.ticketing.model.exceptions.DuplicateOrderException;
import co.com.ticketing.model.exceptions.EventNotFoundException;
import co.com.ticketing.model.exceptions.IdempotencyKeyReuseException;
import co.com.ticketing.model.exceptions.InsufficientTicketsException;
import co.com.ticketing.model.exceptions.InvalidDataException;
import co.com.ticketing.model.exceptions.InvalidTransitionException;
import co.com.ticketing.model.exceptions.OrderConflictException;
import co.com.ticketing.model.exceptions.OrderNotFoundException;
import co.com.ticketing.model.exceptions.PaymentDeclinedException;
import co.com.ticketing.model.exceptions.ReservationExpiredException;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;

@Log4j2
@Component
public class ErrorResponseMapper {

    private static final int UNPROCESSABLE_CONTENT = 422;

    public Mono<ServerResponse> toResponse(Throwable error, ServerRequest request) {
        ProblemDetail problem = switch (error) {
            case BusinessException business -> problem(statusOf(business), business.getMessage());
            case RequestValidationException validation -> {
                ProblemDetail invalid = problem(HttpStatus.BAD_REQUEST, validation.getMessage());
                invalid.setProperty("errors", validation.errors());
                yield invalid;
            }
            case ResponseStatusException status -> problem(status.getStatusCode(), "The request could not be processed");
            default -> {
                log.error("Unexpected error on {} {}", request.method(), request.path(), error);
                yield problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
            }
        };
        problem.setInstance(URI.create(request.path()));
        return ServerResponse.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(problem);
    }

    private static HttpStatusCode statusOf(BusinessException error) {
        return switch (error) {
            case EventNotFoundException _, OrderNotFoundException _ -> HttpStatus.NOT_FOUND;
            case InvalidDataException _ -> HttpStatus.BAD_REQUEST;
            case InsufficientTicketsException _, InvalidTransitionException _, OrderConflictException _,
                 DuplicateOrderException _ -> HttpStatus.CONFLICT;
            case IdempotencyKeyReuseException _ -> HttpStatusCode.valueOf(UNPROCESSABLE_CONTENT);
            case PaymentDeclinedException _ -> HttpStatus.PAYMENT_REQUIRED;
            case ReservationExpiredException _ -> HttpStatus.GONE;
        };
    }

    private static ProblemDetail problem(HttpStatusCode status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
