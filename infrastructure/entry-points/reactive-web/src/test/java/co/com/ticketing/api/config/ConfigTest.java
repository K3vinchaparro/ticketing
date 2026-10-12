package co.com.ticketing.api.config;

import co.com.ticketing.api.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTest {

    private static final WebTestClient CLIENT = WebTestClient
            .bindToRouterFunction(RouterFunctions.route().GET("/ping", _ -> ServerResponse.ok().build()).build())
            .webFilter(new SecurityHeadersConfig(), new CorsConfig().corsWebFilter(List.of("http://localhost:8080")))
            .build();

    @Test
    void responsesCarrySecurityHeaders() {
        CLIENT.get().uri("/ping").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().exists("Content-Security-Policy")
                .expectHeader().exists("Strict-Transport-Security")
                .expectHeader().doesNotExist("Server");
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() {
        CLIENT.get().uri("http://api.example/ping").header("Origin", "http://localhost:8080").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:8080");

        CLIENT.get().uri("http://api.example/ping").header("Origin", "http://evil.example").exchange()
                .expectStatus().isForbidden();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void adminApiKeyIsMandatory(String missingKey) {
        assertThatThrownBy(() -> new ApiProperties(missingKey, Duration.ofSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN_API_KEY");
    }

    @Test
    void propertiesExposeTheirValues() {
        ApiProperties properties = ApiTestSupport.properties(true, 300);

        assertThat(properties.adminApiKey()).isEqualTo(ApiTestSupport.ADMIN_KEY);
        assertThat(properties.rateLimit().requests()).isEqualTo(300);
        assertThat(properties.rateLimit().enabled()).isTrue();
    }
}
