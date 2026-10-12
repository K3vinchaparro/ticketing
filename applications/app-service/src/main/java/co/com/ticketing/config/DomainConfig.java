package co.com.ticketing.config;

import co.com.ticketing.model.order.OrderPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class DomainConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OrderPolicy orderPolicy(OrderPolicyProperties properties) {
        return new OrderPolicy(properties.reservationTtl(), properties.processingTimeout(),
                properties.paymentTimeout(), properties.maxTicketsPerOrder());
    }
}
