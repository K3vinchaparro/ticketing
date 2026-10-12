package co.com.ticketing.api.filter;

import co.com.ticketing.api.config.ApiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminApiKeyFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    public static final String HEADER = "X-Api-Key";

    private final byte[] expectedKey;

    public AdminApiKeyFilter(ApiProperties properties) {
        this.expectedKey = properties.adminApiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        String provided = request.headers().firstHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            return next.handle(request);
        }
        return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "A valid API key is required"));
    }
}
