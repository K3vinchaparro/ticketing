package co.com.ticketing.dynamodb.config;

import co.com.ticketing.dynamodb.entity.AuditEntity;
import co.com.ticketing.dynamodb.entity.EventEntity;
import co.com.ticketing.dynamodb.entity.IdempotencyKeyEntity;
import co.com.ticketing.dynamodb.entity.OrderEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

@Configuration
public class DynamoDbTablesConfig {

    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(DynamoDbAsyncClient client) {
        return DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    public DynamoDbAsyncTable<EventEntity> eventsTable(DynamoDbEnhancedAsyncClient client,
                                                       DynamoDbProperties properties) {
        return client.table(properties.eventsTable(), TableSchema.fromBean(EventEntity.class));
    }

    @Bean
    public DynamoDbAsyncTable<OrderEntity> ordersTable(DynamoDbEnhancedAsyncClient client,
                                                       DynamoDbProperties properties) {
        return client.table(properties.ordersTable(), TableSchema.fromBean(OrderEntity.class));
    }

    @Bean
    public DynamoDbAsyncTable<IdempotencyKeyEntity> idempotencyKeysTable(DynamoDbEnhancedAsyncClient client,
                                                                         DynamoDbProperties properties) {
        return client.table(properties.idempotencyTable(), TableSchema.fromBean(IdempotencyKeyEntity.class));
    }

    @Bean
    public DynamoDbAsyncTable<AuditEntity> auditTable(DynamoDbEnhancedAsyncClient client,
                                                      DynamoDbProperties properties) {
        return client.table(properties.auditTable(), TableSchema.fromBean(AuditEntity.class));
    }
}
