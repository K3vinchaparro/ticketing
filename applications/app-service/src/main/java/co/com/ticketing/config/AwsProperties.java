package co.com.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "aws")
public record AwsProperties(String region, Service dynamodb, Service sqs) {

    public record Service(String endpoint, Duration apiCallTimeout, Duration apiCallAttemptTimeout) {
    }
}
