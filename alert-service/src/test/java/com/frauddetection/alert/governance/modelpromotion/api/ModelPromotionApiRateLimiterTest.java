package com.frauddetection.alert.governance.modelpromotion.api;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

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
