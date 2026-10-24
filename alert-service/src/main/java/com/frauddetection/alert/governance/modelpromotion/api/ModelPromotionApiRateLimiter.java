package com.frauddetection.alert.governance.modelpromotion.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ModelPromotionApiRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int DEFAULT_MAX_TRACKED_ACTORS = 10_000;

    private final Map<String, ArrayDeque<Instant>> requestsByActor = new HashMap<>();
    private final Clock clock;
    private final int maxRequestsPerMinute;
    private final int maxTrackedActors;
    private Instant lastPrunedAt = Instant.EPOCH;

    public ModelPromotionApiRateLimiter(int maxRequestsPerMinute) {
        this(Clock.systemUTC(), maxRequestsPerMinute, DEFAULT_MAX_TRACKED_ACTORS);
    }

    ModelPromotionApiRateLimiter(Clock clock, int maxRequestsPerMinute) {
        this(clock, maxRequestsPerMinute, DEFAULT_MAX_TRACKED_ACTORS);
    }

    ModelPromotionApiRateLimiter(Clock clock, int maxRequestsPerMinute, int maxTrackedActors) {
        this.clock = clock;
        this.maxRequestsPerMinute = Math.max(1, Math.min(maxRequestsPerMinute, 120));
        this.maxTrackedActors = Math.max(1, Math.min(maxTrackedActors, DEFAULT_MAX_TRACKED_ACTORS));
    }

    public boolean allow(String actorId) {
        String key = actorId == null || actorId.isBlank() ? "unknown" : actorId.trim();
        Instant now = clock.instant();
        synchronized (requestsByActor) {
            boolean periodicSweep = !now.isBefore(lastPrunedAt.plus(WINDOW));
            boolean capacitySweep = requestsByActor.size() >= maxTrackedActors
                    && !now.isBefore(lastPrunedAt.plusSeconds(1));
            if (periodicSweep || capacitySweep) {
                pruneExpiredWindows(now);
                lastPrunedAt = now;
            }
            ArrayDeque<Instant> window = requestsByActor.get(key);
            if (window == null) {
                if (requestsByActor.size() >= maxTrackedActors) {
                    return false;
                }
                window = new ArrayDeque<>();
                requestsByActor.put(key, window);
            }
            Instant cutoff = now.minus(WINDOW);
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.removeFirst();
            }
            if (window.size() >= maxRequestsPerMinute) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private void pruneExpiredWindows(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        Iterator<Map.Entry<String, ArrayDeque<Instant>>> windows = requestsByActor.entrySet().iterator();
        while (windows.hasNext()) {
            ArrayDeque<Instant> window = windows.next().getValue();
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.removeFirst();
            }
            if (window.isEmpty()) {
                windows.remove();
            }
        }
    }
}
