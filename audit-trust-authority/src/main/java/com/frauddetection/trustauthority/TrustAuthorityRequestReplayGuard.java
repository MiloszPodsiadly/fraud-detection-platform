package com.frauddetection.trustauthority;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
class TrustAuthorityRequestReplayGuard {

    private static final int MAX_ENTRIES = 10_000;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<String, Instant> seenRequestIds = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    TrustAuthorityRequestReplayGuard() {
        this(Clock.systemUTC());
    }

    TrustAuthorityRequestReplayGuard(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean allow(String callerService, String requestId) {
        Instant now = clock.instant();
        seenRequestIds.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        String cacheKey = callerService + ":" + requestId;
        if (seenRequestIds.containsKey(cacheKey)) {
            return false;
        }
        seenRequestIds.put(cacheKey, now.plus(WINDOW));
        return true;
    }
}
