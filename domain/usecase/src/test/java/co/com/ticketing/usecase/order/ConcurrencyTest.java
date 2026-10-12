package co.com.ticketing.usecase.order;

import co.com.ticketing.model.event.Inventory;
import co.com.ticketing.model.order.Order;
import co.com.ticketing.model.order.OrderStatus;
import co.com.ticketing.model.order.PaymentResult;
import co.com.ticketing.usecase.support.InMemoryOrderRepository;
import org.junit.jupiter.api.RepeatedTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static co.com.ticketing.usecase.support.Fixtures.CLOCK;
import static co.com.ticketing.usecase.support.Fixtures.NOW;
import static co.com.ticketing.usecase.support.Fixtures.POLICY;
import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest {

    private static final int PARALLELISM = 16;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static Order pending(String orderId, int quantity) {
        return Order.pending(orderId, "e1", "c1", quantity, NOW, NOW.plusSeconds(120));
    }

    @RepeatedTest(5)
    void neverSellsMoreTicketsThanTheCapacity() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository(10);
        ProcessOrderUseCase useCase = new ProcessOrderUseCase(repository, POLICY, CLOCK);
        IntStream.range(0, 100).forEach(i -> repository.put(pending("o" + i, 1)));

        List<Order> processed = Flux.range(0, 100)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(i -> useCase.process("o" + i))
                .sequential()
                .collectList()
                .block(TIMEOUT);

        assertThat(processed).hasSize(100);
        assertThat(processed).filteredOn(order -> order.status() == OrderStatus.RESERVED).hasSize(10);
        assertThat(processed).filteredOn(order -> order.status() == OrderStatus.REJECTED).hasSize(90);
        assertThat(repository.inventory()).isEqualTo(new Inventory(0, 10, 0, 0, 0));
    }

    @RepeatedTest(5)
    void ordersOfDifferentSizesNeverExceedTheCapacity() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository(50);
        ProcessOrderUseCase useCase = new ProcessOrderUseCase(repository, POLICY, CLOCK);
        IntStream.range(0, 100).forEach(i -> repository.put(pending("o" + i, 1 + i % 6)));

        Flux.range(0, 100)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(i -> useCase.process("o" + i))
                .sequential()
                .blockLast(TIMEOUT);

        int reservedTickets = repository.orders().stream()
                .filter(order -> order.status() == OrderStatus.RESERVED)
                .mapToInt(Order::quantity)
                .sum();
        Inventory inventory = repository.inventory();
        assertThat(reservedTickets).isEqualTo(inventory.reserved()).isLessThanOrEqualTo(50);
        assertThat(inventory.capacity()).isEqualTo(50);
    }

    @RepeatedTest(5)
    void duplicatedMessagesReserveTheTicketsOnlyOnce() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository(10);
        ProcessOrderUseCase useCase = new ProcessOrderUseCase(repository, POLICY, CLOCK);
        repository.put(pending("o1", 2));

        Flux.range(0, 50)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(_ -> useCase.process("o1"))
                .sequential()
                .blockLast(TIMEOUT);

        assertThat(repository.inventory()).isEqualTo(new Inventory(8, 2, 0, 0, 0));
    }

    @RepeatedTest(20)
    void confirmAndReleaseRaceLeavesTheTicketsInExactlyOneState() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository(10);
        repository.put(pending("o1", 2));
        new ProcessOrderUseCase(repository, POLICY, CLOCK).process("o1").block(TIMEOUT);
        repository.put(repository.findById("o1").block(TIMEOUT).withDeadline(NOW));

        Clock justBeforeDeadline = Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC);
        ConfirmOrderUseCase confirm = new ConfirmOrderUseCase(repository,
                _ -> Mono.just(PaymentResult.APPROVED), POLICY, justBeforeDeadline);
        ReleaseExpiredReservationsUseCase release = new ReleaseExpiredReservationsUseCase(repository, CLOCK);

        Mono<Order> confirming = confirm.confirm("o1")
                .subscribeOn(Schedulers.parallel())
                .onErrorResume(_ -> Mono.empty());
        Mono<Long> releasing = release.execute().count().subscribeOn(Schedulers.parallel());
        Mono.when(confirming, releasing).block(TIMEOUT);

        OrderStatus finalStatus = repository.findById("o1").block(TIMEOUT).status();
        Inventory inventory = repository.inventory();
        assertThat(finalStatus).isIn(OrderStatus.SOLD, OrderStatus.RELEASED);
        assertThat(inventory).isEqualTo(finalStatus == OrderStatus.SOLD
                ? new Inventory(8, 0, 0, 2, 0)
                : Inventory.ofCapacity(10));
    }

    @RepeatedTest(5)
    void sameIdempotencyKeyUnderConcurrencyCreatesASingleOrder() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository(10);

        Long created = Flux.range(0, 50)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(i -> repository.create(pending("o" + i, 1), "same-key")
                        .map(_ -> 1)
                        .onErrorReturn(0))
                .sequential()
                .filter(result -> result == 1)
                .count()
                .block(TIMEOUT);

        assertThat(created).isEqualTo(1);
        assertThat(repository.orders()).hasSize(1);
    }
}
