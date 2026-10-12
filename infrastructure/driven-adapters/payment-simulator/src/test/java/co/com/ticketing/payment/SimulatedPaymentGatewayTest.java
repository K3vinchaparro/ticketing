package co.com.ticketing.payment;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.PaymentResult;
import co.com.ticketing.payment.config.PaymentSimulatorProperties;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedPaymentGatewayTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Order ORDER = Order.pending("o1", "e1", "c1", 2, NOW, NOW.plusSeconds(120));

    private static SimulatedPaymentGateway gateway(double approvalRate, Duration latency) {
        return new SimulatedPaymentGateway(new PaymentSimulatorProperties(approvalRate, latency));
    }

    @Test
    void approvesEveryChargeWhenTheRateIsOne() {
        StepVerifier.create(gateway(1.0, Duration.ZERO).charge(ORDER))
                .expectNext(PaymentResult.APPROVED)
                .verifyComplete();
    }

    @Test
    void declinesEveryChargeWhenTheRateIsZero() {
        StepVerifier.create(gateway(0.0, Duration.ZERO).charge(ORDER))
                .expectNext(PaymentResult.DECLINED)
                .verifyComplete();
    }

    @Test
    void answersAfterTheConfiguredLatencyWithoutBlocking() {
        StepVerifier.withVirtualTime(() -> gateway(1.0, Duration.ofSeconds(2)).charge(ORDER))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .expectNext(PaymentResult.APPROVED)
                .verifyComplete();
    }

    @Test
    void latencyDefaultsToZero() {
        assertThat(new PaymentSimulatorProperties(1.0, null).latency()).isEqualTo(Duration.ZERO);
    }

    @Test
    void approvalRateMustBeAProbability() {
        assertThatThrownBy(() -> new PaymentSimulatorProperties(1.5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentSimulatorProperties(-0.1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
