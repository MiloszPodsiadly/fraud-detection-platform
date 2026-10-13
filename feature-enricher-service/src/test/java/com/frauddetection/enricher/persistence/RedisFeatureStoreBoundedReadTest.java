package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisFeatureStoreBoundedReadTest {

    @Test
    void usesTheBoundedSortedSetRangeOverloadWithTheCanonicalSentinelLimit() {
        Fixture fixture = fixture(FraudFeatureValueBoundsContract.MAX_RECENT_TRANSACTION_COUNT);
        when(fixture.sortedSets().rangeByScoreWithScores(
                anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()
        )).thenReturn(Set.of());

        fixture.store().loadSnapshot(event());

        verify(fixture.sortedSets()).rangeByScoreWithScores(
                anyString(),
                anyDouble(),
                anyDouble(),
                eq(0L),
                eq((long) FraudFeatureValueBoundsContract.MAX_RECENT_TRANSACTION_COUNT)
        );
        verify(fixture.sortedSets(), never()).rangeByScoreWithScores(anyString(), anyDouble(), anyDouble());
    }

    @Test
    void acceptsOnlyThePreviousTransactionsRepresentableAfterAddingTheCurrentTransaction() {
        Fixture fixture = fixture(3);
        when(fixture.sortedSets().rangeByScoreWithScores(
                anyString(), anyDouble(), anyDouble(), eq(0L), eq(3L)
        )).thenReturn(entries(2));

        var snapshot = fixture.store().loadSnapshot(event());

        assertThat(snapshot.recentTransactionCount()).isEqualTo(2);
        assertThat(snapshot.recentTransactions()).hasSize(2);
    }

    @Test
    void rejectsTheOverflowSentinelBeforePublishingPartialEvidence() {
        Fixture fixture = fixture(3);
        when(fixture.sortedSets().rangeByScoreWithScores(
                anyString(), anyDouble(), anyDouble(), eq(0L), eq(3L)
        )).thenReturn(entries(3));

        assertThatThrownBy(() -> fixture.store().loadSnapshot(event()))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasRootCauseMessage("RECENT_TRANSACTION_WINDOW_CAPACITY_EXCEEDED");
    }

    private Fixture fixture(int maxFinalCount) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> sortedSets = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> sets = mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(sortedSets);
        when(redisTemplate.opsForSet()).thenReturn(sets);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(sortedSets.count(anyString(), anyDouble(), anyDouble())).thenReturn(0L);
        when(sets.isMember(anyString(), anyString())).thenReturn(false);
        RedisFeatureStore store = new RedisFeatureStore(
                redisTemplate,
                new FeatureStoreProperties(
                        Duration.ofMinutes(1),
                        Duration.ofDays(7),
                        Duration.ofDays(8),
                        Duration.ofDays(180),
                        Duration.ofDays(30)
                ),
                new CurrencyAmountConverter(),
                maxFinalCount
        );
        return new Fixture(store, sortedSets);
    }

    private Set<ZSetOperations.TypedTuple<String>> entries(int count) {
        Set<ZSetOperations.TypedTuple<String>> entries = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            entries.add(new DefaultTypedTuple<>(
                    "transaction-" + index + "|1.00|PLN|1.00",
                    1_776_679_000_000.0d + index
            ));
        }
        return entries;
    }

    private TransactionRawEvent event() {
        return TransactionFixtures.rawTransaction().build();
    }

    private record Fixture(
            RedisFeatureStore store,
            ZSetOperations<String, String> sortedSets
    ) {
    }
}
