package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RegulatedMutationInspectionRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, ArrayDeque<Instant>> requestsByIdentity = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxRequestsPerMinute;

    @Autowired
    public RegulatedMutationInspectionRateLimiter(
            @Value("${app.regulated-mutation.inspection.max-per-minute:30}") int maxRequestsPerMinute
    ) {
        this(Clock.systemUTC(), maxRequestsPerMinute);
    }

    RegulatedMutationInspectionRateLimiter(Clock clock, int maxRequestsPerMinute) {
        this.clock = clock;
        this.maxRequestsPerMinute = Math.max(1, Math.min(maxRequestsPerMinute, 120));
    }

    public boolean allow(String identity) {
        String key = identity == null || identity.isBlank() ? "unknown" : identity.trim();
        Instant now = clock.instant();
        ArrayDeque<Instant> window = requestsByIdentity.computeIfAbsent(key, ignored -> new ArrayDeque<>());
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
