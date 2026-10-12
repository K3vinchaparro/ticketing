package co.com.ticketing.config;

import co.com.ticketing.model.order.OrderPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTest {

    private static final AwsProperties.Service LOCAL =
            new AwsProperties.Service("http://localhost:8000", Duration.ofSeconds(5), Duration.ofSeconds(2));
    private static final AwsProperties.Service AWS =
            new AwsProperties.Service("", Duration.ofSeconds(5), Duration.ofSeconds(2));

    private final AwsClientsConfig awsClientsConfig = new AwsClientsConfig();
    private final MetricPublisher metricPublisher = Mockito.mock(MetricPublisher.class);

    @Test
    void clientsUseTheLocalEndpointWhenOneIsConfigured() {
        AwsProperties properties = new AwsProperties("us-east-1", LOCAL, LOCAL);

        try (DynamoDbAsyncClient dynamo = awsClientsConfig.dynamoDbAsyncClient(properties, metricPublisher);
             SqsAsyncClient sqs = awsClientsConfig.sqsAsyncClient(properties, metricPublisher)) {
            assertThat(dynamo.serviceClientConfiguration().endpointOverride())
                    .hasValueSatisfying(endpoint -> assertThat(endpoint.getPort()).isEqualTo(8000));
            assertThat(sqs.serviceClientConfiguration().region().id()).isEqualTo("us-east-1");
        }
    }

    @Test
    void clientsUseTheRegionalEndpointWhenNoneIsConfigured() {
        AwsProperties properties = new AwsProperties("us-east-1", AWS, new AwsProperties.Service(null,
                Duration.ofSeconds(30), Duration.ofSeconds(25)));

        try (DynamoDbAsyncClient dynamo = awsClientsConfig.dynamoDbAsyncClient(properties, metricPublisher);
             SqsAsyncClient sqs = awsClientsConfig.sqsAsyncClient(properties, metricPublisher)) {
            assertThat(dynamo.serviceClientConfiguration().endpointOverride()).isEmpty();
            assertThat(sqs.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void domainBeansAreBuiltFromConfiguration() {
        DomainConfig domainConfig = new DomainConfig();
        OrderPolicyProperties properties = new OrderPolicyProperties(Duration.ofMinutes(10), Duration.ofMinutes(2),
                Duration.ofMinutes(2), 6);

        OrderPolicy policy = domainConfig.orderPolicy(properties);

        assertThat(policy).isEqualTo(new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(2),
                Duration.ofMinutes(2), 6));
        assertThat(domainConfig.clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
