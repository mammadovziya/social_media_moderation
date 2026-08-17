package com.example.moderation.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small process-local L1 for immutable, usage-stripped, configuration-bound AI evidence. */
final class CompletedAiEnvelopeCache {
    static final int DEFAULT_MAXIMUM_SIZE = 4_096;
    static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final int maximumSize;
    private final long ttlNanos;
    private final LinkedHashMap<String, Entry> entries =
            new LinkedHashMap<>(128, 0.75f, true);

    CompletedAiEnvelopeCache() {
        this(DEFAULT_MAXIMUM_SIZE, DEFAULT_TTL);
    }

    CompletedAiEnvelopeCache(int maximumSize, Duration ttl) {
        if (maximumSize < 1 || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("completed AI cache bounds must be positive");
        }
        this.maximumSize = maximumSize;
        this.ttlNanos = ttl.toNanos();
    }

    synchronized Map<String, Object> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.storedAtNanos() >= ttlNanos) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    synchronized void put(String key, Map<String, Object> value) {
        put(key, value, System.nanoTime());
    }

    /** Uses the conservative start of durable completion so local expiry cannot outlive it. */
    synchronized void put(
            String key, Map<String, Object> value, long completionStartedAtNanos) {
        entries.put(key, new Entry(Map.copyOf(value), completionStartedAtNanos));
        while (entries.size() > maximumSize) {
            String eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    synchronized int size() {
        return entries.size();
    }

    private record Entry(Map<String, Object> value, long storedAtNanos) {}
}
