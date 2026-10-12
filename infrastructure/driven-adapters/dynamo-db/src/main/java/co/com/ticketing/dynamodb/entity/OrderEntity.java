package co.com.ticketing.dynamodb.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;

@DynamoDbBean
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {

    public static final String OVERDUE_INDEX = "status-expiresAt-index";
    public static final String ORDER_ID = "orderId";
    public static final String STATUS = "status";
    public static final String EXPIRES_AT = "expiresAt";
    public static final String UPDATED_AT = "updatedAt";
    public static final String VERSION = "version";

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String orderId;
    private String eventId;
    private String customerId;
    private Integer quantity;
    @Getter(onMethod_ = @DynamoDbSecondaryPartitionKey(indexNames = OVERDUE_INDEX))
    private String status;
    @Getter(onMethod_ = @DynamoDbSecondarySortKey(indexNames = OVERDUE_INDEX))
    private Long expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;
}
