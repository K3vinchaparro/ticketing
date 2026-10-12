package co.com.ticketing.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "api")
public record ApiProperties(String adminApiKey, Duration availabilityPollInterval, RateLimit rateLimit) {

    public ApiProperties {
        if (adminApiKey == null || adminApiKey.isBlank()) {
            throw new IllegalArgumentException("api.admin-api-key must be provided (ADMIN_API_KEY)");
        }
    }

    public record RateLimit(boolean enabled, int requests, Duration window) {
    }
}
