package co.com.ticketing.api.filter;

import co.com.ticketing.api.config.ApiProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements WebFilter {

    private static final String API_PREFIX = "/api/";
    private static final String BODY = """
            {"type":"about:blank","title":"Too Many Requests","status":429,\
            "detail":"Request quota exceeded, retry later"}""";

    private final ApiProperties.RateLimit settings;
    private final FixedWindowRateLimiter limiter;

    public RateLimitFilter(ApiProperties properties) {
        this.settings = properties.rateLimit();
        this.limiter = new FixedWindowRateLimiter(settings.requests(), settings.window());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        boolean limited = settings.enabled() && exchange.getRequest().getPath().value().startsWith(API_PREFIX);
        if (!limited || limiter.tryAcquire(clientKey(exchange))) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set("Retry-After", Long.toString(Math.max(1, settings.window().toSeconds())));
        DataBuffer body = response.bufferFactory().wrap(BODY.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(body));
    }

    private static String clientKey(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
