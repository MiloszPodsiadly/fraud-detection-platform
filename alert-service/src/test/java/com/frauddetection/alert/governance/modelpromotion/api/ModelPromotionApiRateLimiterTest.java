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
}
