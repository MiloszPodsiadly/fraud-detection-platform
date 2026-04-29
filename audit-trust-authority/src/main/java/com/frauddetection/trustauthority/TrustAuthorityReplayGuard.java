package com.frauddetection.trustauthority;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
class TrustAuthorityReplayGuard {

    private static final int MAX_ENTRIES = 10_000;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<String, SeenPayload> seenPayloads = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SeenPayload> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    TrustAuthorityReplayGuard() {
        this(Clock.systemUTC());
    }

    TrustAuthorityReplayGuard(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean allow(String payloadHash, String context) {
        Instant now = clock.instant();
        seenPayloads.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        SeenPayload existing = seenPayloads.get(payloadHash);
        if (existing == null) {
            seenPayloads.put(payloadHash, new SeenPayload(context, now.plus(WINDOW)));
            return true;
        }
        return existing.context().equals(context);
    }

    private record SeenPayload(String context, Instant expiresAt) {
    }
}
