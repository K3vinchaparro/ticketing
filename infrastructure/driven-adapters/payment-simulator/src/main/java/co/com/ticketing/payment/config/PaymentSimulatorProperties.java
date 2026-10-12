package co.com.ticketing.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "adapter.payment")
public record PaymentSimulatorProperties(double approvalRate, Duration latency) {

    public PaymentSimulatorProperties {
        if (approvalRate < 0 || approvalRate > 1) {
            throw new IllegalArgumentException("adapter.payment.approval-rate must be between 0 and 1");
        }
        latency = latency == null ? Duration.ZERO : latency;
    }
}
