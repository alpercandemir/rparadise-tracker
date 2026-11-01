package com.radioparadise.tracker.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded in-process cache. Loads are coalesced; TTL starts after a successful load. */
public final class ExpiringCache<K, V> {
    private record Entry<V>(V value, long loadedAt) {}

    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier clock;

    public ExpiringCache(int capacity, Duration ttl) {
        this(capacity, ttl, System::nanoTime);
    }

    ExpiringCache(int capacity, Duration ttl, LongSupplier clock) {
        if (capacity < 1 || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Invalid cache bounds");
        }
        this.capacity = capacity;
        this.ttlNanos = ttl.toNanos();
        this.clock = clock;
    }

    public synchronized V get(K key, Supplier<V> loadValue) {
        Entry<V> entry = entries.get(key);
        if (entry != null && clock.getAsLong() - entry.loadedAt() < ttlNanos) {
            return entry.value();
        }
        V value = loadValue.get();
        entries.put(key, new Entry<>(value, clock.getAsLong()));
        while (entries.size() > capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
        return value;
    }

    public synchronized void clear() {
        entries.clear();
    }
}
