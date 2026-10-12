package co.com.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ticketing.orders")
public record OrderPolicyProperties(Duration reservationTtl, Duration processingTimeout, Duration paymentTimeout,
                                    int maxTicketsPerOrder) {
}
