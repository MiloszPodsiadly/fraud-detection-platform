package com.frauddetection.trustauthority;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
class TrustAuthorityRateLimiter {

    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    TrustAuthorityRateLimiter() {
        this(Clock.systemUTC());
    }

    TrustAuthorityRateLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean allow(String callerIdentity, int limitPerMinute) {
        int boundedLimit = Math.max(1, limitPerMinute);
        Instant now = clock.instant();
        long minute = now.getEpochSecond() / 60;
        Window window = windows.get(callerIdentity);
        if (window == null || window.minute() != minute) {
            windows.put(callerIdentity, new Window(minute, 1));
            return true;
        }
        if (window.count() >= boundedLimit) {
            return false;
        }
        windows.put(callerIdentity, new Window(minute, window.count() + 1));
        return true;
    }

    private record Window(long minute, int count) {
    }
}
