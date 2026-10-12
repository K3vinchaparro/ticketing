package co.com.ticketing.dynamodb.helper;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;

public final class Attributes {

    private Attributes() {
    }

    public static AttributeValue string(String value) {
        return AttributeValue.fromS(value);
    }

    public static AttributeValue number(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    public static AttributeValue timestamp(Instant value) {
        return AttributeValue.fromS(value.toString());
    }

    public static AttributeValue epochSeconds(Instant value) {
        return number(value.getEpochSecond());
    }
}
