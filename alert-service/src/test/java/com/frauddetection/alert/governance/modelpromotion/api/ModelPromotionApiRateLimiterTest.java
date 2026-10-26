package com.frauddetection.alert.governance.modelpromotion.api;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ModelPromotionApiRateLimiterTest {

    @Test
    void limitsRequestsPerActorWithinTheConfiguredWindow() {
        ModelPromotionApiRateLimiter rateLimiter = new ModelPromotionApiRateLimiter(
                Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC),
                2
        );

        assertThat(rateLimiter.allow("analyst-1")).isTrue();
        assertThat(rateLimiter.allow("analyst-1")).isTrue();
        assertThat(rateLimiter.allow("analyst-1")).isFalse();
        assertThat(rateLimiter.allow("analyst-2")).isTrue();
    }

    @Test
    void boundsTrackedActorsAndEvictsExpiredWindowsBeforeAdmittingNewActors() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T12:00:00Z"));
        ModelPromotionApiRateLimiter rateLimiter = new ModelPromotionApiRateLimiter(clock, 2, 1);

        assertThat(rateLimiter.allow("analyst-1")).isTrue();
        assertThat(rateLimiter.allow("analyst-2")).isFalse();

        clock.advance(java.time.Duration.ofMinutes(1).plusSeconds(1));

        assertThat(rateLimiter.allow("analyst-2")).isTrue();
    }

    @Test
    void redisAndClusterCrossSlotFailuresFailClosedWithoutActorLabels() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("redis unavailable"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(registry);
        ModelPromotionApiRateLimiter rateLimiter = new ModelPromotionApiRateLimiter(redis, Clock.systemUTC(),
                java.time.Duration.ofMinutes(1), 30, 100, metrics);

        assertThat(rateLimiter.check("issuer-a#sensitive-subject")).isEqualTo(ModelPromotionApiRateLimiter.Decision.UNAVAILABLE);
        assertThat(registry.get("model_promotion_api_rate_limit_total")
                .tag("outcome", "UNAVAILABLE").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("model_promotion_api_rate_limit_total").tag("outcome", "UNAVAILABLE")
                .counter().getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactly("outcome");

        StringRedisTemplate clusterRedis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("CROSSSLOT Keys in request do not hash to the same slot"))
                .when(clusterRedis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        ModelPromotionApiRateLimiter clusterLimiter = new ModelPromotionApiRateLimiter(clusterRedis,
                Clock.systemUTC(), java.time.Duration.ofMinutes(1), 30, 100, metrics);
        assertThat(clusterLimiter.check("actor-1")).isEqualTo(ModelPromotionApiRateLimiter.Decision.UNAVAILABLE);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> clusterLimiter.enforce("actor-2")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(failure -> assertThat(((org.springframework.web.server.ResponseStatusException) failure)
                        .getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
