package co.com.ticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;

@Configuration
public class AwsClientsConfig {

    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient(AwsProperties properties, MetricPublisher metricPublisher) {
        return configure(DynamoDbAsyncClient.builder(), properties.region(), properties.dynamodb(), metricPublisher)
                .build();
    }

    @Bean
    public SqsAsyncClient sqsAsyncClient(AwsProperties properties, MetricPublisher metricPublisher) {
        return configure(SqsAsyncClient.builder(), properties.region(), properties.sqs(), metricPublisher)
                .build();
    }

    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder, String region,
                                                                 AwsProperties.Service service,
                                                                 MetricPublisher metricPublisher) {
        builder.region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(service.apiCallTimeout())
                        .apiCallAttemptTimeout(service.apiCallAttemptTimeout())
                        .addMetricPublisher(metricPublisher));
        if (service.endpoint() != null && !service.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(service.endpoint()));
        }
        return builder;
    }
}
