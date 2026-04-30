package com.frauddetection.alert.audit.external;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalAuditCoverageRateLimiterTest {

    @Test
    void shouldAllowWithinSharedRedisCostBudgetWithoutRawIdentityInKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), eq(25L))).thenReturn(25L);

        ExternalAuditCoverageRateLimiter limiter = new ExternalAuditCoverageRateLimiter(redisTemplate, 100);

        assertThat(limiter.allow("ops-admin@example.test", 25)).isTrue();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).increment(keyCaptor.capture(), eq(25L));
        assertThat(keyCaptor.getValue()).startsWith("audit:coverage:rate:");
        assertThat(keyCaptor.getValue()).doesNotContain("ops-admin");
        verify(redisTemplate).expire(keyCaptor.getValue(), Duration.ofMinutes(1));
    }

    @Test
    void shouldDenyWhenSharedRedisCostBudgetIsExceeded() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), eq(100L))).thenReturn(101L);

        ExternalAuditCoverageRateLimiter limiter = new ExternalAuditCoverageRateLimiter(redisTemplate, 100);

        assertThat(limiter.allow("10.0.0.8", 500)).isFalse();
    }

    @Test
    void shouldFailClosedWhenRedisIsUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("redis unavailable"));

        ExternalAuditCoverageRateLimiter limiter = new ExternalAuditCoverageRateLimiter(redisTemplate, 100);

        assertThat(limiter.allow("10.0.0.8", 10)).isFalse();
    }
}
