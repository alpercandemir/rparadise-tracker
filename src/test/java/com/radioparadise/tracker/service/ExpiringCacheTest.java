package com.radioparadise.tracker.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class ExpiringCacheTest {
    @Test
    void cachesUntilExpiryAndBoundsEntries() {
        AtomicLong time = new AtomicLong();
        var cache = new ExpiringCache<String, Integer>(2, Duration.ofSeconds(30), time::get);
        assertThat(cache.get("a", () -> 1)).isEqualTo(1);
        time.set(Duration.ofSeconds(29).toNanos());
        assertThat(cache.get("a", () -> 9)).isEqualTo(1);
        time.set(Duration.ofSeconds(30).toNanos());
        assertThat(cache.get("a", () -> 2)).isEqualTo(2);
        cache.get("b", () -> 3);
        cache.get("c", () -> 4);
        assertThat(cache.get("a", () -> 5)).isEqualTo(5);
    }

    @Test
    void failuresDoNotPoisonCache() {
        var cache = new ExpiringCache<String, Integer>(2, Duration.ofSeconds(30));
        assertThatThrownBy(() -> cache.get("a", () -> { throw new IllegalStateException("offline"); }))
                .hasMessage("offline");
        assertThat(cache.get("a", () -> 2)).isEqualTo(2);
    }

    @Test
    void concurrentMissesShareOneLoad() throws Exception {
        var cache = new ExpiringCache<String, Integer>(2, Duration.ofSeconds(30));
        AtomicInteger calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> executor.submit(() -> cache.get("a", calls::incrementAndGet))).toList();
            for (var task : tasks) assertThat(task.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(calls.get()).isEqualTo(1);
    }
}
