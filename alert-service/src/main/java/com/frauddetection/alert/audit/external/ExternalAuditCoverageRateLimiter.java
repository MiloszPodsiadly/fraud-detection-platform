package com.frauddetection.alert.audit.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ExternalAuditCoverageRateLimiter {

    private final Clock clock;
    private final int maxRequestsPerMinute;
    private final AtomicLong windowEpochMinute = new AtomicLong(-1L);
    private final AtomicInteger requestsInWindow = new AtomicInteger(0);

    @Autowired
    public ExternalAuditCoverageRateLimiter(@Value("${app.audit.external-integrity.coverage.max-per-minute:30}") int maxRequestsPerMinute) {
        this(Clock.systemUTC(), maxRequestsPerMinute);
    }

    ExternalAuditCoverageRateLimiter(Clock clock, int maxRequestsPerMinute) {
        this.clock = clock;
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
    }

    public boolean allow() {
        long minute = Instant.now(clock).getEpochSecond() / 60L;
        long current = windowEpochMinute.get();
        if (current != minute && windowEpochMinute.compareAndSet(current, minute)) {
            requestsInWindow.set(0);
        }
        return requestsInWindow.incrementAndGet() <= maxRequestsPerMinute;
    }
}
