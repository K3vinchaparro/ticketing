package co.com.ticketing.api.filter;

import co.com.ticketing.api.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

class FiltersTest {

    private static final RouterFunction<ServerResponse> ROUTES = route()
            .GET("/api/v1/ping", _ -> Mono.deferContextual(context ->
                    ServerResponse.ok().bodyValue(context.getOrDefault(CorrelationIdFilter.CONTEXT_KEY, "none"))))
            .GET("/actuator/health", _ -> ServerResponse.ok().bodyValue("UP"))
            .build();

    private static WebTestClient client(boolean rateLimitEnabled, int requests) {
        return WebTestClient.bindToRouterFunction(ROUTES)
                .webFilter(new CorrelationIdFilter(),
                        new RateLimitFilter(ApiTestSupport.properties(rateLimitEnabled, requests)))
                .build();
    }

    @Test
    void validCorrelationIdIsKeptAndReachesTheHandler() {
        client(false, 1).get().uri("/api/v1/ping")
                .header("X-Correlation-Id", "abc-123")
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "abc-123")
                .expectBody(String.class).isEqualTo("abc-123");
    }

    @Test
    void missingCorrelationIdIsGenerated() {
        client(false, 1).get().uri("/api/v1/ping")
                .exchange()
                .expectHeader().value("X-Correlation-Id", id -> assertThat(id).hasSize(36));
    }

    @Test
    void unsafeCorrelationIdIsReplaced() {
        client(false, 1).get().uri("/api/v1/ping")
                .header("X-Correlation-Id", "bad id <script>")
                .exchange()
                .expectHeader().value("X-Correlation-Id", id -> assertThat(id).doesNotContain("script").hasSize(36));
    }

    @Test
    void requestsOverTheQuotaAreRejected() {
        WebTestClient client = client(true, 2);

        client.get().uri("/api/v1/ping").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/ping").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/ping").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("Retry-After", "10")
                .expectBody().jsonPath("$.status").isEqualTo(429);
    }

    @Test
    void operationalEndpointsAreNotRateLimited() {
        WebTestClient client = client(true, 1);

        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void limiterCanBeDisabled() {
        WebTestClient client = client(false, 1);

        client.get().uri("/api/v1/ping").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/ping").exchange().expectStatus().isOk();
    }

    @Test
    void eachClientHasItsOwnQuota() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofSeconds(10));

        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
        assertThat(limiter.tryAcquire("10.0.0.2")).isTrue();
    }

    @Test
    void quotaIsRestoredWhenTheWindowEnds() throws InterruptedException {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofMillis(50));

        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
        Thread.sleep(120);
        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
    }
}
