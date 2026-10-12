package co.com.ticketing.dynamodb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "adapter.dynamodb")
public record DynamoDbProperties(
        String eventsTable,
        String ordersTable,
        String idempotencyTable,
        String auditTable,
        Duration idempotencyTtl,
        int overdueBatchSize) {
}
