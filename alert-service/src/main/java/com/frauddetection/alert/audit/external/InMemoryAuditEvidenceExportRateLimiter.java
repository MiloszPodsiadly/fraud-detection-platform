package com.frauddetection.alert.audit.external;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(
        name = "app.audit.evidence-export.rate-limiter",
        havingValue = "in-memory",
        matchIfMissing = true
)
public class InMemoryAuditEvidenceExportRateLimiter implements AuditEvidenceExportRateLimiterStrategy {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, ArrayDeque<Instant>> exportsByActor = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxExportsPerMinute;

    @Autowired
    public InMemoryAuditEvidenceExportRateLimiter(@Value("${app.audit.evidence-export.max-per-minute:5}") int maxExportsPerMinute) {
        this(Clock.systemUTC(), maxExportsPerMinute);
    }

    InMemoryAuditEvidenceExportRateLimiter(Clock clock, int maxExportsPerMinute) {
        this.clock = clock;
        this.maxExportsPerMinute = Math.max(1, Math.min(maxExportsPerMinute, 20));
    }

    @Override
    public boolean allow(String actorId) {
        String key = actorId == null || actorId.isBlank() ? "unknown" : actorId.trim();
        Instant now = clock.instant();
        ArrayDeque<Instant> actorWindow = exportsByActor.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (actorWindow) {
            Instant cutoff = now.minus(WINDOW);
            while (!actorWindow.isEmpty() && actorWindow.peekFirst().isBefore(cutoff)) {
                actorWindow.removeFirst();
            }
            if (actorWindow.size() >= maxExportsPerMinute) {
                return false;
            }
            actorWindow.addLast(now);
            return true;
        }
    }
}
