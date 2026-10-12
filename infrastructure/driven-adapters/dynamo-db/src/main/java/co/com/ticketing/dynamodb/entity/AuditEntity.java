package co.com.ticketing.dynamodb.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEntity {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String entityId;
    @Getter(onMethod_ = @DynamoDbSortKey)
    private String occurredAt;
    private String entityType;
    private String eventId;
    private Integer quantity;
    private String reason;
    private String fromOrderStatus;
    private String toOrderStatus;
    private String fromTicketStatus;
    private String toTicketStatus;
}
