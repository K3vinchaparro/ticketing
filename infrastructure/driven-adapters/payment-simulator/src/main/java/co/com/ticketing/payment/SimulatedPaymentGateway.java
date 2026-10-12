package co.com.ticketing.payment;

import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.PaymentResult;
import co.com.ticketing.model.order.gateways.PaymentGateway;
import co.com.ticketing.payment.config.PaymentSimulatorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class SimulatedPaymentGateway implements PaymentGateway {

    private final PaymentSimulatorProperties properties;

    @Override
    public Mono<PaymentResult> charge(Order order) {
        return Mono.delay(properties.latency())
                .map(_ -> ThreadLocalRandom.current().nextDouble() < properties.approvalRate()
                        ? PaymentResult.APPROVED
                        : PaymentResult.DECLINED);
    }
}
