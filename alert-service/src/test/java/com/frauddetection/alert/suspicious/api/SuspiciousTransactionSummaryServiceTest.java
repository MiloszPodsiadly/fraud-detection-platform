package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.suspicious.SuspiciousTransactionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspiciousTransactionSummaryServiceTest {

    private final SuspiciousTransactionRepository repository = mock(SuspiciousTransactionRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-19T10:00:00Z"));

    @Test
    void summaryFirstRequestComputesCount() {
        when(repository.count()).thenReturn(98L);

        SuspiciousTransactionSummaryResponse response = service(Duration.ofSeconds(30)).summary();

        assertThat(response.totalSuspiciousTransactions()).isEqualTo(98L);
        assertThat(response.freshness()).isEqualTo(SuspiciousTransactionSummaryFreshness.FRESH);
        assertThat(response.cachedAt()).isEqualTo(Instant.parse("2026-05-19T10:00:00Z"));
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-05-19T10:00:30Z"));
        verify(repository).count();
    }

    @Test
    void summaryRepeatedRequestsWithinTtlDoNotRecount() {
        when(repository.count()).thenReturn(98L, 101L);
        SuspiciousTransactionSummaryService service = service(Duration.ofSeconds(30));

        SuspiciousTransactionSummaryResponse first = service.summary();
        clock.advance(Duration.ofSeconds(5));
        SuspiciousTransactionSummaryResponse second = service.summary();

        assertThat(first.totalSuspiciousTransactions()).isEqualTo(98L);
        assertThat(second.totalSuspiciousTransactions()).isEqualTo(98L);
        assertThat(second.freshness()).isEqualTo(SuspiciousTransactionSummaryFreshness.FRESH);
        verify(repository, times(1)).count();
    }

    @Test
    void summaryAfterTtlRefreshesCount() {
        when(repository.count()).thenReturn(98L, 101L);
        SuspiciousTransactionSummaryService service = service(Duration.ofSeconds(30));

        service.summary();
        clock.advance(Duration.ofSeconds(31));
        SuspiciousTransactionSummaryResponse response = service.summary();

        assertThat(response.totalSuspiciousTransactions()).isEqualTo(101L);
        assertThat(response.freshness()).isEqualTo(SuspiciousTransactionSummaryFreshness.FRESH);
        verify(repository, times(2)).count();
    }

    @Test
    void summaryRefreshFailureReturnsStaleCachedValue() {
        when(repository.count()).thenReturn(98L).thenThrow(new IllegalStateException("mongo unavailable"));
        SuspiciousTransactionSummaryService service = service(Duration.ofSeconds(30));

        service.summary();
        clock.advance(Duration.ofSeconds(31));
        SuspiciousTransactionSummaryResponse response = service.summary();

        assertThat(response.totalSuspiciousTransactions()).isEqualTo(98L);
        assertThat(response.freshness()).isEqualTo(SuspiciousTransactionSummaryFreshness.STALE);
        assertThat(response.cachedAt()).isEqualTo(Instant.parse("2026-05-19T10:00:00Z"));
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-05-19T10:00:30Z"));
        verify(repository, times(2)).count();
    }

    @Test
    void summaryRefreshFailureWithoutCacheIsControlled() {
        when(repository.count()).thenThrow(new IllegalStateException("mongo unavailable"));

        SuspiciousTransactionSummaryResponse response = service(Duration.ofSeconds(30)).summary();

        assertThat(response.totalSuspiciousTransactions()).isZero();
        assertThat(response.freshness()).isEqualTo(SuspiciousTransactionSummaryFreshness.UNAVAILABLE);
        assertThat(response.cachedAt()).isNull();
        assertThat(response.expiresAt()).isNull();
    }

    @Test
    void repeatedSummaryCallsDoNotCallRepositoryCountPerRequest() {
        when(repository.count()).thenReturn(98L, 101L, 102L);
        SuspiciousTransactionSummaryService service = service(Duration.ofSeconds(30));

        service.summary();
        service.summary();
        service.summary();

        verify(repository, times(1)).count();
    }

    @Test
    void invalidTtlFailsFast() {
        SuspiciousTransactionSummaryProperties properties = new SuspiciousTransactionSummaryProperties();
        properties.setCacheTtl(Duration.ZERO);

        assertThatThrownBy(() -> new SuspiciousTransactionSummaryService(repository, properties, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.suspicious-transactions.summary.cache-ttl must be positive");
    }

    private SuspiciousTransactionSummaryService service(Duration ttl) {
        SuspiciousTransactionSummaryProperties properties = new SuspiciousTransactionSummaryProperties();
        properties.setCacheTtl(ttl);
        return new SuspiciousTransactionSummaryService(repository, properties, clock);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
