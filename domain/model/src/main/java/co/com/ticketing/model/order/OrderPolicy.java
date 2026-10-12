package co.com.ticketing.model.order;

import co.com.ticketing.model.exceptions.InvalidDataException;

import java.time.Duration;

public record OrderPolicy(Duration reservationTtl, Duration processingTimeout, Duration paymentTimeout,
                          int maxTicketsPerOrder) {

    public OrderPolicy {
        requirePositive(reservationTtl, "reservationTtl");
        requirePositive(processingTimeout, "processingTimeout");
        requirePositive(paymentTimeout, "paymentTimeout");
        if (maxTicketsPerOrder < 1) {
            throw new InvalidDataException("maxTicketsPerOrder must be at least 1");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new InvalidDataException(field + " must be positive");
        }
    }
}
