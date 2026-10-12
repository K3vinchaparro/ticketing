package co.com.ticketing.dynamodb.entity;

import co.com.ticketing.model.ticket.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

@DynamoDbBean
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEntity {

    public static final String EVENT_ID = "eventId";
    public static final String VERSION = "version";

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String eventId;
    private String name;
    private Instant date;
    private String venue;
    private Integer capacity;
    private Integer available;
    private Integer reserved;
    private Integer pendingConfirmation;
    private Integer sold;
    private Integer complimentary;
    private Long version;
    private Instant createdAt;

    public static String counterOf(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> "available";
            case RESERVED -> "reserved";
            case PENDING_CONFIRMATION -> "pendingConfirmation";
            case SOLD -> "sold";
            case COMPLIMENTARY -> "complimentary";
        };
    }
}
