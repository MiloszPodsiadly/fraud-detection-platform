package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared Redis sliding-window limiter; in-memory mode is reserved for explicit single-node use. */
public class ModelPromotionApiRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int DEFAULT_MAX_TRACKED_ACTORS = 10_000;
    private static final String KEY_PREFIX = "model-promotion:api-rate:v1:";
    private static final DefaultRedisScript<Long> REDIS_WINDOW = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)
            local window = tonumber(ARGV[1])
            local actor = ARGV[2]
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            if not redis.call('ZSCORE', KEYS[2], actor) and redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then
              return -1
            end
            redis.call('ZADD', KEYS[2], now + window, actor)
            redis.call('PEXPIRE', KEYS[2], window * 2)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
              return 0
            end
            redis.call('ZADD', KEYS[1], now, ARGV[5])
            redis.call('PEXPIRE', KEYS[1], window)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Duration window;
    private final int maxRequestsPerMinute;
    private final int maxTrackedActors;
    private final Map<String, ArrayDeque<Instant>> requestsByActor;
    private final AlertServiceMetrics metrics;
    private Instant lastPrunedAt = Instant.EPOCH;

    public ModelPromotionApiRateLimiter(StringRedisTemplate redis, int maxRequestsPerMinute, int maxTrackedActors) {
        this(redis, Clock.systemUTC(), WINDOW, maxRequestsPerMinute, maxTrackedActors, null);
    }

    ModelPromotionApiRateLimiter(StringRedisTemplate redis, Clock clock, Duration window,
            int maxRequestsPerMinute, int maxTrackedActors, AlertServiceMetrics metrics) {
        this.redis = redis;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.window = window == null || window.isZero() || window.isNegative() ? WINDOW : window;
        this.maxRequestsPerMinute = Math.max(1, Math.min(maxRequestsPerMinute, 120));
        this.maxTrackedActors = Math.max(1, Math.min(maxTrackedActors, DEFAULT_MAX_TRACKED_ACTORS));
        this.requestsByActor = null;
        this.metrics = metrics;
    }

    /** Explicit single-node/development mode. */
    public ModelPromotionApiRateLimiter(int maxRequestsPerMinute) {
        this(Clock.systemUTC(), WINDOW, maxRequestsPerMinute, DEFAULT_MAX_TRACKED_ACTORS);
    }

    ModelPromotionApiRateLimiter(Clock clock, int maxRequestsPerMinute) {
        this(clock, WINDOW, maxRequestsPerMinute, DEFAULT_MAX_TRACKED_ACTORS);
    }

    ModelPromotionApiRateLimiter(Clock clock, int maxRequestsPerMinute, int maxTrackedActors) {
        this(clock, WINDOW, maxRequestsPerMinute, maxTrackedActors);
    }

    ModelPromotionApiRateLimiter(Clock clock, int maxRequestsPerMinute, int maxTrackedActors,
            AlertServiceMetrics metrics) {
        this(clock, WINDOW, maxRequestsPerMinute, maxTrackedActors, metrics);
    }

    ModelPromotionApiRateLimiter(Clock clock, Duration window, int maxRequestsPerMinute, int maxTrackedActors) {
        this(clock, window, maxRequestsPerMinute, maxTrackedActors, null);
    }

    private ModelPromotionApiRateLimiter(Clock clock, Duration window, int maxRequestsPerMinute,
            int maxTrackedActors, AlertServiceMetrics metrics) {
        this.redis = null;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.window = window == null || window.isZero() || window.isNegative() ? WINDOW : window;
        this.maxRequestsPerMinute = Math.max(1, Math.min(maxRequestsPerMinute, 120));
        this.maxTrackedActors = Math.max(1, Math.min(maxTrackedActors, DEFAULT_MAX_TRACKED_ACTORS));
        this.requestsByActor = new HashMap<>();
        this.metrics = metrics;
    }

    public Decision check(String authenticatedActorId) {
        if (!StringUtils.hasText(authenticatedActorId)) return record(Decision.UNAVAILABLE);
        Decision result = redis == null ? checkLocal(authenticatedActorId.trim()) : checkRedis(authenticatedActorId.trim());
        return record(result);
    }

    private Decision record(Decision result) {
        if (metrics != null) {
            try {
                metrics.recordModelPromotionApiRateLimit(result.name());
            } catch (RuntimeException ignored) {
                // Rate limit enforcement must not depend on the metrics backend.
            }
        }
        return result;
    }

    /** Retained for local tests and explicit single-node callers. */
    public boolean allow(String authenticatedActorId) {
        return check(authenticatedActorId) == Decision.ALLOWED;
    }

    public void enforce(String authenticatedActorId) {
        switch (check(authenticatedActorId)) {
            case ALLOWED -> { }
            case RATE_LIMITED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Model promotion API rate limit exceeded.");
            case UNAVAILABLE -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Model promotion API rate limiter is unavailable.");
        }
    }

    private Decision checkRedis(String actorId) {
        try {
            String actorHash = sha256(actorId);
            Long result = redis.execute(REDIS_WINDOW,
                    List.of(KEY_PREFIX + "actor:" + actorHash, KEY_PREFIX + "actors"),
                    Long.toString(window.toMillis()), actorHash, Integer.toString(maxRequestsPerMinute),
                    Integer.toString(maxTrackedActors), UUID.randomUUID().toString());
            if (result == null || result < 0) return Decision.UNAVAILABLE;
            return result == 1L ? Decision.ALLOWED : Decision.RATE_LIMITED;
        } catch (RuntimeException unavailable) {
            return Decision.UNAVAILABLE;
        }
    }

    private Decision checkLocal(String actorId) {
        Instant now = clock.instant();
        synchronized (requestsByActor) {
            boolean periodicSweep = !now.isBefore(lastPrunedAt.plus(window));
            boolean capacitySweep = requestsByActor.size() >= maxTrackedActors
                    && !now.isBefore(lastPrunedAt.plusSeconds(1));
            if (periodicSweep || capacitySweep) {
                pruneExpiredWindows(now);
                lastPrunedAt = now;
            }
            ArrayDeque<Instant> requests = requestsByActor.get(actorId);
            if (requests == null) {
                if (requestsByActor.size() >= maxTrackedActors) return Decision.UNAVAILABLE;
                requests = new ArrayDeque<>();
                requestsByActor.put(actorId, requests);
            }
            Instant cutoff = now.minus(window);
            while (!requests.isEmpty() && !requests.peekFirst().isAfter(cutoff)) requests.removeFirst();
            if (requests.size() >= maxRequestsPerMinute) return Decision.RATE_LIMITED;
            requests.addLast(now);
            return Decision.ALLOWED;
        }
    }

    private void pruneExpiredWindows(Instant now) {
        Instant cutoff = now.minus(window);
        Iterator<Map.Entry<String, ArrayDeque<Instant>>> windows = requestsByActor.entrySet().iterator();
        while (windows.hasNext()) {
            ArrayDeque<Instant> requests = windows.next().getValue();
            while (!requests.isEmpty() && !requests.peekFirst().isAfter(cutoff)) requests.removeFirst();
            if (requests.isEmpty()) windows.remove();
        }
    }

    private String sha256(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public enum Decision { ALLOWED, RATE_LIMITED, UNAVAILABLE }
}
