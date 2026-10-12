package co.com.ticketing.api.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class FixedWindowRateLimiter {

    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final int limit;
    private final Cache<String, AtomicInteger> counters;

    public FixedWindowRateLimiter(int limit, Duration window) {
        this.limit = limit;
        this.counters = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(MAX_TRACKED_CLIENTS)
                .build();
    }

    public boolean tryAcquire(String clientKey) {
        return counters.get(clientKey, _ -> new AtomicInteger()).incrementAndGet() <= limit;
    }
}
