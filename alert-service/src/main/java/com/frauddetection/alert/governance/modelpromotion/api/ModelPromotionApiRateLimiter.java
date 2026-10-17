package com.frauddetection.alert.governance.modelpromotion.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ModelPromotionApiRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, ArrayDeque<Instant>> requestsByActor = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxRequestsPerMinute;

    public ModelPromotionApiRateLimiter(
            @Value("${app.model-promotion.api.max-requests-per-minute:30}") int maxRequestsPerMinute
    ) {
        this(Clock.systemUTC(), maxRequestsPerMinute);
    }

    ModelPromotionApiRateLimiter(Clock clock, int maxRequestsPerMinute) {
        this.clock = clock;
        this.maxRequestsPerMinute = Math.max(1, Math.min(maxRequestsPerMinute, 120));
    }

    public boolean allow(String actorId) {
        String key = actorId == null || actorId.isBlank() ? "unknown" : actorId.trim();
        Instant now = clock.instant();
        ArrayDeque<Instant> window = requestsByActor.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
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
}
