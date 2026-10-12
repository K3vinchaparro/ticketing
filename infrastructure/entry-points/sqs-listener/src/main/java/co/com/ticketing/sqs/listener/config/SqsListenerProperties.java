package co.com.ticketing.sqs.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "entrypoint.sqs")
public record SqsListenerProperties(
        boolean enabled,
        String queueUrl,
        int waitTimeSeconds,
        int visibilityTimeoutSeconds,
        int maxNumberOfMessages,
        int concurrency,
        Duration processingTimeout) {
}
