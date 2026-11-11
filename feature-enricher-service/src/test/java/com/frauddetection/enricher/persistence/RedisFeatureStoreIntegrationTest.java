package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.service.TransactionFeatureCalculator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisFeatureStoreIntegrationTest {

    private static final Instant CURRENT_TIMESTAMP = Instant.parse("2026-04-20T10:15:28Z");
    private static final String CUSTOMER_ID = "redis-snapshot-customer";

    private RedisFeatureStore featureStore;
    private StringRedisTemplate redisTemplate;
    private LettuceConnectionFactory connectionFactory;

    @BeforeAll
    void startRedis() {
        if (!FraudPlatformContainers.redis().isRunning()) {
            FraudPlatformContainers.redis().start();
        }
        connectionFactory = new LettuceConnectionFactory(
                FraudPlatformContainers.redis().getHost(),
                FraudPlatformContainers.redis().getMappedPort(6379)
        );
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        featureStore = new RedisFeatureStore(
                redisTemplate,
                new FeatureStoreProperties(
                        Duration.ofMinutes(1),
                        Duration.ofDays(7),
                        Duration.ofDays(8),
                        Duration.ofDays(180),
                        Duration.ofDays(30)
                ),
                new CurrencyAmountConverter()
        );
    }

    @AfterAll
    void closeRedisConnection() {
        connectionFactory.destroy();
    }

    @AfterEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void derivesEmptyAndMultiCurrencySnapshotsFromTheirExactMembership() {
        TransactionRawEvent current = event("current", CURRENT_TIMESTAMP, new BigDecimal("1.00"), "PLN");

        assertConsistent(featureStore.loadSnapshot(current), 0, BigDecimal.ZERO, List.of());

        featureStore.recordTransaction(event("eur-previous", CURRENT_TIMESTAMP.minusSeconds(40), new BigDecimal("10.00"), "EUR"));
        featureStore.recordTransaction(event("usd-previous", CURRENT_TIMESTAMP.minusSeconds(20), new BigDecimal("5.00"), "USD"));

        assertConsistent(
                featureStore.loadSnapshot(current),
                2,
                new BigDecimal("63.00"),
                List.of("eur-previous", "usd-previous")
        );
    }

    @Test
    void preservesTheExistingExclusiveLowerAndInclusiveUpperWindowBoundaries() {
        String key = customerTransactionsKey();
        redisTemplate.opsForZSet().add(key, "at-lower-bound|1.00|PLN|1.00", score(CURRENT_TIMESTAMP.minusSeconds(60)));
        redisTemplate.opsForZSet().add(key, "inside-window|2.00|PLN|2.00", score(CURRENT_TIMESTAMP.minusSeconds(60).plusMillis(1)));
        redisTemplate.opsForZSet().add(key, "at-upper-bound|3.00|PLN|3.00", score(CURRENT_TIMESTAMP));
        redisTemplate.opsForZSet().add(key, "future|4.00|PLN|4.00", score(CURRENT_TIMESTAMP.plusMillis(1)));

        assertConsistent(
                featureStore.loadSnapshot(event("current", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN")),
                2,
                new BigDecimal("5.00"),
                List.of("inside-window", "at-upper-bound")
        );
    }

    @Test
    void recentWindowAcceptsMMinusOneAndFailsAtMAndMPlusOnePriorRows() {
        RedisFeatureStore boundedStore = new RedisFeatureStore(redisTemplate,
                new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofDays(8),
                        Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter(), 3);
        TransactionRawEvent current = event("current", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN");

        featureStore.recordTransaction(event("previous-1", CURRENT_TIMESTAMP.minusSeconds(3), BigDecimal.ONE, "PLN"));
        featureStore.recordTransaction(event("previous-2", CURRENT_TIMESTAMP.minusSeconds(2), BigDecimal.ONE, "PLN"));
        assertThat(boundedStore.loadSnapshot(current).recentTransactionCount()).isEqualTo(2);

        featureStore.recordTransaction(event("previous-3", CURRENT_TIMESTAMP.minusSeconds(1), BigDecimal.ONE, "PLN"));
        assertThatThrownBy(() -> boundedStore.loadSnapshot(current))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasRootCauseMessage("RECENT_TRANSACTION_WINDOW_CAPACITY_EXCEEDED");

        featureStore.recordTransaction(event("previous-4", CURRENT_TIMESTAMP.minusMillis(500), BigDecimal.ONE, "PLN"));
        assertThatThrownBy(() -> boundedStore.loadSnapshot(current))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasRootCauseMessage("RECENT_TRANSACTION_WINDOW_CAPACITY_EXCEEDED");
    }

    @Test
    void failsClosedForMalformedDuplicateOrMonetarilyInconsistentMembership() {
        TransactionRawEvent current = event("current", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN");

        assertMalformed(current, List.of("malformed"));
        assertMalformed(current, List.of(
                "duplicate|1.00|PLN|1.00",
                "duplicate|2.00|PLN|2.00"
        ));
        assertMalformed(current, List.of("tampered|1.00|EUR|99.00"));
    }

    @Test
    void concurrentWritesCannotSeparateCountFromTheMembershipUsedForTheSum() throws Exception {
        TransactionRawEvent current = event("current", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> {
                await(start);
                for (int index = 0; index < 40; index++) {
                    featureStore.recordTransaction(event(
                            "concurrent-" + index,
                            CURRENT_TIMESTAMP.minusSeconds(50).plusMillis(index),
                            BigDecimal.ONE,
                            "PLN"
                    ));
                }
            });
            var reader = executor.submit(() -> {
                await(start);
                for (int index = 0; index < 40; index++) {
                    FeatureStoreSnapshot snapshot = featureStore.loadSnapshot(current);
                    assertThat(snapshot.recentTransactionCount()).isEqualTo(snapshot.recentTransactions().size());
                    assertThat(snapshot.recentAmountSumPln()).isEqualByComparingTo(sum(snapshot));
                }
            });

            start.countDown();
            writer.get();
            reader.get();
        }

        FeatureStoreSnapshot completed = featureStore.loadSnapshot(current);
        assertThat(completed.recentTransactionCount()).isEqualTo(40);
        assertThat(completed.recentAmountSumPln()).isEqualByComparingTo("40.00");
        assertThat(completed.recentTransactions()).extracting("transactionId").doesNotHaveDuplicates();
    }

    @Test
    void attestedHistoryIsAtomicIdempotentClusterColocatedAndMonotonic() {
        TransactionRawEvent raw = event("attested-main", CURRENT_TIMESTAMP, new BigDecimal("42.00"), "PLN");
        FeatureNamespace namespace = attestedNamespace();
        TransactionEnrichedEvent enriched = enriched(raw);
        String commitment = SourceEventCommitment.attestedHistory("a".repeat(64), enriched);

        featureStore.recordAttestedTransaction(enriched, namespace, commitment);
        featureStore.recordAttestedTransaction(enriched, namespace, commitment); // Ambiguous ACK retry is a no-op.
        featureStore.verifyAttestedTransaction(enriched, namespace, commitment);

        var next = event("attested-next", CURRENT_TIMESTAMP.plusSeconds(1), BigDecimal.ONE, "PLN");
        FeatureStoreSnapshot snapshot = featureStore.loadSnapshot(next, namespace);
        assertThat(snapshot.recentTransactionCount()).isEqualTo(1);
        assertThat(snapshot.recentAmountSumPln()).isEqualByComparingTo("42.00");
        assertThat(snapshot.merchantFrequency7d()).isEqualTo(1);
        assertThat(snapshot.knownDevice()).isTrue();
        assertThat(snapshot.lastTransactionTimestamp()).isEqualTo(CURRENT_TIMESTAMP);
        assertThat(featureStore.hasRecordedTransaction(raw, namespace)).isTrue();

        var older = event("attested-older", CURRENT_TIMESTAMP.minusSeconds(5), BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent olderEnriched = enriched(older);
        assertThatThrownBy(() -> featureStore.recordAttestedTransaction(olderEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), olderEnriched)))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
        assertThat(featureStore.loadSnapshot(next, namespace).lastTransactionTimestamp()).isEqualTo(CURRENT_TIMESTAMP);

        Set<String> keys = redisTemplate.keys("feature:v3:attested:*");
        assertThat(keys).hasSize(6);
        assertThat(keys.stream().map(RedisFeatureStoreIntegrationTest::redisHashTag).collect(Collectors.toSet())).hasSize(1);
        assertThat(keys.stream().map(key -> redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS))
                .allMatch(ttl -> ttl != null && ttl > 0)).isTrue();

        Map<String, Object> changedSnapshot = new java.util.HashMap<>(enriched.featureSnapshot());
        changedSnapshot.put("recentTransactionCount", 99);
        TransactionEnrichedEvent changed = new TransactionEnrichedEvent(enriched.eventId(), enriched.transactionId(),
                enriched.correlationId(), enriched.customerId(), enriched.accountId(), enriched.createdAt(),
                enriched.transactionTimestamp(), enriched.transactionAmount(), enriched.merchantInfo(), enriched.deviceInfo(),
                enriched.locationInfo(), enriched.customerContext(), changedSnapshot);
        String changedCommitment = SourceEventCommitment.attestedHistory("a".repeat(64), changed);
        assertThatThrownBy(() -> featureStore.recordAttestedTransaction(changed, namespace, changedCommitment))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_HISTORY_COMMITMENT_CONFLICT");

        String processedKey = keys.stream().filter(key -> key.endsWith(":customer:processed-transactions")).findFirst().orElseThrow();
        redisTemplate.delete(processedKey);
        assertThatThrownBy(() -> featureStore.verifyAttestedTransaction(enriched, namespace, commitment))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
    }

    @Test
    void lateAttestedEventDoesNotScoreFromHistoryPrunedByALaterEvent() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent first = event("late-a", CURRENT_TIMESTAMP, new BigDecimal("10.00"), "PLN");
        TransactionEnrichedEvent firstEnriched = enriched(first);
        featureStore.recordAttestedTransaction(firstEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), firstEnriched));

        TransactionRawEvent later = event("late-b", CURRENT_TIMESTAMP.plus(Duration.ofMinutes(2)),
                new BigDecimal("20.00"), "PLN");
        FeatureStoreSnapshot laterSnapshot = featureStore.loadSnapshot(later, namespace);
        TransactionEnrichedEvent laterEnriched = enriched(later);
        featureStore.recordAttestedTransaction(laterEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), laterEnriched));

        TransactionRawEvent delayed = event("late-c", CURRENT_TIMESTAMP.plusSeconds(30),
                new BigDecimal("30.00"), "PLN");
        assertThat(laterSnapshot.recentTransactionCount()).isZero();
        assertThatThrownBy(() -> featureStore.loadSnapshot(delayed, namespace))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
        assertThat(featureStore.hasRecordedTransaction(first, namespace)).isTrue();
        assertThat(featureStore.hasRecordedTransaction(later, namespace)).isTrue();
        assertThat(featureStore.hasRecordedTransaction(delayed, namespace)).isFalse();
    }

    @Test
    void missingCommitmentMarkerCannotAdoptSurvivingBusinessMembership() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent raw = event("marker-loss", CURRENT_TIMESTAMP, new BigDecimal("8.00"), "PLN");
        TransactionEnrichedEvent enriched = enriched(raw);
        String originalCommitment = SourceEventCommitment.attestedHistory("a".repeat(64), enriched);
        featureStore.recordAttestedTransaction(enriched, namespace, originalCommitment);

        String processedKey = redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:processed-transactions")).findFirst().orElseThrow();
        redisTemplate.opsForHash().delete(processedKey, raw.transactionId());
        assertThatThrownBy(() -> featureStore.hasRecordedTransaction(raw, namespace))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
        String replacementCommitment = SourceEventCommitment.attestedHistory("b".repeat(64), enriched);

        assertThatThrownBy(() -> featureStore.recordAttestedTransaction(enriched, namespace, replacementCommitment))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
        assertThat(redisTemplate.opsForHash().get(processedKey, raw.transactionId())).isNull();
        assertThat(redisTemplate.opsForSet().members(redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:transaction-ids")).findFirst().orElseThrow()))
                .containsExactly(raw.transactionId());
        assertThat(redisTemplate.opsForZSet().zCard(redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:transactions")).findFirst().orElseThrow())).isEqualTo(1L);
    }

    @Test
    void exactAppliedHistoryRemainsVerifiableAfterNaturalRecentWindowPruning() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent original = event("pruned-original", CURRENT_TIMESTAMP,
                new BigDecimal("8.00"), "PLN");
        TransactionEnrichedEvent originalEnriched = enriched(original);
        String originalCommitment = SourceEventCommitment.attestedHistory("a".repeat(64), originalEnriched);
        featureStore.recordAttestedTransaction(originalEnriched, namespace, originalCommitment);

        TransactionRawEvent later = event("pruning-later", CURRENT_TIMESTAMP.plus(Duration.ofMinutes(2)),
                BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent laterEnriched = enriched(later);
        featureStore.loadSnapshot(later, namespace);
        featureStore.recordAttestedTransaction(laterEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), laterEnriched));

        assertThat(featureStore.hasRecordedTransaction(original, namespace)).isTrue();
        featureStore.verifyAttestedTransaction(originalEnriched, namespace, originalCommitment);
        featureStore.recordAttestedTransaction(originalEnriched, namespace, originalCommitment);

        String customerTransactions = redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:transactions")).findFirst().orElseThrow();
        String customerIds = redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:transaction-ids")).findFirst().orElseThrow();
        assertThat(redisTemplate.opsForZSet().zCard(customerTransactions)).isEqualTo(1L);
        assertThat(redisTemplate.opsForSet().members(customerIds)).containsExactlyInAnyOrder(
                original.transactionId(), later.transactionId());
    }

    @Test
    void missingRecentMembershipInsideActiveWindowStillFailsClosed() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent raw = event("active-member", CURRENT_TIMESTAMP, new BigDecimal("8.00"), "PLN");
        TransactionEnrichedEvent enriched = enriched(raw);
        String commitment = SourceEventCommitment.attestedHistory("a".repeat(64), enriched);
        featureStore.recordAttestedTransaction(enriched, namespace, commitment);

        String customerTransactions = redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:transactions")).findFirst().orElseThrow();
        String member = raw.transactionId() + "|8.00|PLN|8.00";
        assertThat(redisTemplate.opsForZSet().remove(customerTransactions, member)).isEqualTo(1L);

        assertThatThrownBy(() -> featureStore.verifyAttestedTransaction(enriched, namespace, commitment))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
    }

    @Test
    void missingAttestedTransactionIdentitySurvivalMarkerFailsClosed() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent raw = event("missing-survival-marker", CURRENT_TIMESTAMP, new BigDecimal("8.00"), "PLN");
        TransactionEnrichedEvent enriched = enriched(raw);
        String commitment = SourceEventCommitment.attestedHistory("a".repeat(64), enriched);
        featureStore.recordAttestedTransaction(enriched, namespace, commitment);

        String identityKey = redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:transaction-ids")).findFirst().orElseThrow();
        assertThat(redisTemplate.opsForSet().remove(identityKey, raw.transactionId())).isEqualTo(1L);

        assertThatThrownBy(() -> featureStore.verifyAttestedTransaction(enriched, namespace, commitment))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
    }

    @Test
    void expiredAttestedTransactionIdentitySurvivalIndexFailsClosed() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent raw = event("expired-survival-index", CURRENT_TIMESTAMP, new BigDecimal("8.00"), "PLN");
        TransactionEnrichedEvent enriched = enriched(raw);
        String commitment = SourceEventCommitment.attestedHistory("a".repeat(64), enriched);
        featureStore.recordAttestedTransaction(enriched, namespace, commitment);

        String identityKey = redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:transaction-ids")).findFirst().orElseThrow();
        assertThat(redisTemplate.expire(identityKey, Duration.ZERO)).isTrue();

        assertThatThrownBy(() -> featureStore.verifyAttestedTransaction(enriched, namespace, commitment))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
    }

    @Test
    void exactAppliedHistoryRemainsVerifiableAfterNaturalMerchantWindowPruning() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent original = event("merchant-pruned-original", CURRENT_TIMESTAMP,
                new BigDecimal("8.00"), "PLN");
        TransactionEnrichedEvent originalEnriched = enriched(original);
        String originalCommitment = SourceEventCommitment.attestedHistory("a".repeat(64), originalEnriched);
        featureStore.recordAttestedTransaction(originalEnriched, namespace, originalCommitment);

        TransactionRawEvent later = event("merchant-pruning-later",
                CURRENT_TIMESTAMP.plus(Duration.ofDays(8)), BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent laterEnriched = enriched(later);
        featureStore.loadSnapshot(later, namespace);
        featureStore.recordAttestedTransaction(laterEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), laterEnriched));

        featureStore.verifyAttestedTransaction(originalEnriched, namespace, originalCommitment);
    }

    @Test
    void attestedMerchantFrequencyUsesExactBoundedV2V3Union() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent existing = event("merchant-shared", CURRENT_TIMESTAMP.minusSeconds(10), BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent enriched = enriched(existing);
        featureStore.recordAttestedTransaction(enriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), enriched));

        String merchantId = existing.merchantInfo().merchantId();
        String v2MerchantKey = "feature:v2:attested:" + namespace.keySegment() + ":customer:" + CUSTOMER_ID
                + ":merchant:" + merchantId + ":transactions";
        redisTemplate.opsForZSet().add(v2MerchantKey, existing.transactionId(), score(existing.transactionTimestamp()));
        redisTemplate.opsForZSet().add(v2MerchantKey, "merchant-v2-only", score(CURRENT_TIMESTAMP.minusSeconds(5)));

        FeatureStoreSnapshot snapshot = featureStore.loadSnapshot(
                event("merchant-current", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN"), namespace);

        assertThat(snapshot.merchantFrequency7d()).isEqualTo(2);
    }

    @Test
    void attestedMerchantDualReadRejectsConflictingDuplicateTimestamps() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent existing = event("merchant-conflict", CURRENT_TIMESTAMP.minusSeconds(10), BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent enriched = enriched(existing);
        featureStore.recordAttestedTransaction(enriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), enriched));
        String v2MerchantKey = "feature:v2:attested:" + namespace.keySegment() + ":customer:" + CUSTOMER_ID
                + ":merchant:" + existing.merchantInfo().merchantId() + ":transactions";
        redisTemplate.opsForZSet().add(v2MerchantKey, existing.transactionId(), score(existing.transactionTimestamp().plusMillis(1)));

        assertThatThrownBy(() -> featureStore.loadSnapshot(
                event("merchant-current", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN"), namespace))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasRootCauseMessage("MALFORMED_MERCHANT_TRANSACTION_MEMBERSHIP");
    }

    @Test
    void forcedV2AndV3InterleavingsAreBoundedButNotPointInTimeSnapshots() throws Exception {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent original = event("snapshot-before-v3-write", CURRENT_TIMESTAMP.minusSeconds(40), BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent originalEnriched = enriched(original);
        featureStore.recordAttestedTransaction(originalEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), originalEnriched));

        var v3WriterRaw = withDevice(event("snapshot-v3-write", CURRENT_TIMESTAMP.minusSeconds(5),
                new BigDecimal("2.00"), "PLN"), "device-v3-interleaved");
        var v3Current = withDevice(event("snapshot-v3-current", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN"),
                "device-v3-interleaved");
        String v3CustomerKey = privateKey("customerTransactionsKey", new Class<?>[] {FeatureNamespace.class, String.class},
                namespace, CUSTOMER_ID);
        var v3Paused = new CountDownLatch(1);
        var v3Release = new CountDownLatch(1);
        RedisFeatureStore v3Reader = pauseAfterZSetRange(v3CustomerKey, v3Paused, v3Release);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var snapshotFuture = executor.submit(() -> v3Reader.loadSnapshot(v3Current, namespace));
            assertThat(v3Paused.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            TransactionEnrichedEvent v3Writer = enriched(v3WriterRaw);
            featureStore.recordAttestedTransaction(v3Writer, namespace,
                    SourceEventCommitment.attestedHistory("a".repeat(64), v3Writer));
            String v2Prefix = "feature:v2:attested:" + namespace.keySegment() + ":customer:" + CUSTOMER_ID;
            redisTemplate.opsForZSet().add(v2Prefix + ":transactions",
                    v3WriterRaw.transactionId() + "|2.00|PLN|2.00", score(v3WriterRaw.transactionTimestamp()));
            redisTemplate.opsForZSet().add(v2Prefix + ":merchant:" + v3WriterRaw.merchantInfo().merchantId() + ":transactions",
                    v3WriterRaw.transactionId(), score(v3WriterRaw.transactionTimestamp()));
            redisTemplate.opsForSet().add(v2Prefix + ":devices", v3WriterRaw.deviceInfo().deviceId());
            redisTemplate.opsForValue().set(v2Prefix + ":last-transaction", v3WriterRaw.transactionTimestamp().toString());
            v3Release.countDown();
            FeatureStoreSnapshot mixedV3 = snapshotFuture.get();
            assertThat(mixedV3.recentTransactionCount()).isEqualTo(2);
            assertThat(mixedV3.recentTransactions()).extracting("transactionId")
                    .containsExactly(original.transactionId(), v3WriterRaw.transactionId());
            assertThat(mixedV3.merchantFrequency7d()).isEqualTo(2);
            assertThat(mixedV3.knownDevice()).isTrue();
            assertThat(mixedV3.lastTransactionTimestamp()).isEqualTo(v3WriterRaw.transactionTimestamp());
        } finally {
            v3Release.countDown();
        }

        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        var v2WriterRaw = withDevice(event("snapshot-v2-write", CURRENT_TIMESTAMP.minusSeconds(5),
                new BigDecimal("2.00"), "PLN"), "device-v2-interleaved");
        var v2Current = withDevice(event("snapshot-v2-current", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN"),
                "device-v2-interleaved");
        String v2Prefix = "feature:v2:attested:" + namespace.keySegment() + ":customer:" + CUSTOMER_ID;
        String v2CustomerKey = v2Prefix + ":transactions";
        var v2Paused = new CountDownLatch(1);
        var v2Release = new CountDownLatch(1);
        RedisFeatureStore v2Reader = pauseAfterZSetRange(v2CustomerKey, v2Paused, v2Release);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var snapshotFuture = executor.submit(() -> v2Reader.loadSnapshot(v2Current, namespace));
            assertThat(v2Paused.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String member = v2WriterRaw.transactionId() + "|2.00|PLN|2.00";
            redisTemplate.opsForZSet().add(v2CustomerKey, member, score(v2WriterRaw.transactionTimestamp()));
            redisTemplate.opsForZSet().add(v2Prefix + ":merchant:" + v2WriterRaw.merchantInfo().merchantId() + ":transactions",
                    v2WriterRaw.transactionId(), score(v2WriterRaw.transactionTimestamp()));
            redisTemplate.opsForSet().add(v2Prefix + ":devices", v2WriterRaw.deviceInfo().deviceId());
            redisTemplate.opsForValue().set(v2Prefix + ":last-transaction", v2WriterRaw.transactionTimestamp().toString());
            v2Release.countDown();
            FeatureStoreSnapshot mixedV2 = snapshotFuture.get();
            assertThat(mixedV2.recentTransactionCount()).isZero();
            assertThat(mixedV2.merchantFrequency7d()).isEqualTo(1);
            assertThat(mixedV2.knownDevice()).isTrue();
            assertThat(mixedV2.lastTransactionTimestamp()).isEqualTo(v2WriterRaw.transactionTimestamp());
        } finally {
            v2Release.countDown();
        }
    }

    @Test
    void malformedAttestedLastTimestampDoesNotPartiallyApplyHistory() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent existing = event("valid-last", CURRENT_TIMESTAMP, new BigDecimal("5.00"), "PLN");
        TransactionEnrichedEvent existingEnriched = enriched(existing);
        featureStore.recordAttestedTransaction(existingEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), existingEnriched));

        Set<String> keys = redisTemplate.keys("feature:v3:attested:*");
        String customerTransactionsKey = keys.stream().filter(key -> key.endsWith(":customer:transactions")).findFirst().orElseThrow();
        String processedKey = keys.stream().filter(key -> key.endsWith(":customer:processed-transactions")).findFirst().orElseThrow();
        String lastKey = keys.stream().filter(key -> key.endsWith(":customer:last-transaction")).findFirst().orElseThrow();
        redisTemplate.opsForValue().set(lastKey, "malformed-timestamp");

        TransactionRawEvent rejected = event("bad-last", CURRENT_TIMESTAMP.plusSeconds(1), new BigDecimal("7.00"), "PLN");
        TransactionEnrichedEvent rejectedEnriched = enriched(rejected);
        String rejectedCommitment = SourceEventCommitment.attestedHistory("a".repeat(64), rejectedEnriched);
        assertThatThrownBy(() -> featureStore.recordAttestedTransaction(rejectedEnriched, namespace, rejectedCommitment))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
        assertThat(redisTemplate.opsForZSet().score(customerTransactionsKey, "bad-last|7.00|PLN|7.00")).isNull();
        assertThat(redisTemplate.opsForHash().get(processedKey, "bad-last")).isNull();
    }

    @Test
    void attestedEqualTimestampIsAcceptedButOneMillisecondRegressionFailsClosed() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent first = event("ordered-first", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent firstEnriched = enriched(first);
        featureStore.recordAttestedTransaction(firstEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), firstEnriched));

        TransactionRawEvent sameTimestamp = event("ordered-same-time", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN");
        FeatureStoreSnapshot sameTimeSnapshot = featureStore.loadSnapshot(sameTimestamp, namespace);
        assertThat(sameTimeSnapshot.recentTransactions()).extracting("transactionId").containsExactly("ordered-first");
        TransactionEnrichedEvent sameTimeEnriched = enriched(sameTimestamp);
        featureStore.recordAttestedTransaction(sameTimeEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), sameTimeEnriched));

        TransactionRawEvent oneMillisecondEarlier = event("ordered-earlier", CURRENT_TIMESTAMP.minusMillis(1), BigDecimal.ONE, "PLN");
        assertThatThrownBy(() -> featureStore.loadSnapshot(oneMillisecondEarlier, namespace))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
    }

    @Test
    void attestedWindowKeepsExclusiveLowerAndInclusiveUpperBoundaries() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent lower = event("attested-lower", CURRENT_TIMESTAMP.minusSeconds(60), BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent lowerEnriched = enriched(lower);
        featureStore.recordAttestedTransaction(lowerEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), lowerEnriched));
        TransactionRawEvent upper = event("attested-upper", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent upperEnriched = enriched(upper);
        featureStore.recordAttestedTransaction(upperEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), upperEnriched));

        FeatureStoreSnapshot snapshot = featureStore.loadSnapshot(upper, namespace);

        assertThat(snapshot.recentTransactionCount()).isEqualTo(1);
        assertThat(snapshot.recentTransactions()).extracting("transactionId").containsExactly("attested-upper");
        assertThat(snapshot.merchantFrequency7d()).isEqualTo(2);
    }

    @Test
    void v2WatermarkRegressionFailsBeforePruningV3Members() throws Exception {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent existing = event("v2-watermark-existing", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent existingEnriched = enriched(existing);
        featureStore.recordAttestedTransaction(existingEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), existingEnriched));
        String v2LastKey = privateKey("v2LastTransactionKey", new Class<?>[]{FeatureNamespace.class, String.class},
                namespace, CUSTOMER_ID);
        redisTemplate.opsForValue().set(v2LastKey, CURRENT_TIMESTAMP.plus(Duration.ofMinutes(2)).toString());

        TransactionRawEvent regressed = event("v2-watermark-regressed", CURRENT_TIMESTAMP.plus(Duration.ofMinutes(1)),
                BigDecimal.ONE, "PLN");
        assertThatThrownBy(() -> featureStore.loadSnapshot(regressed, namespace))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
        String customerTransactionsKey = redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:transactions")).findFirst().orElseThrow();
        String existingMember = privateKey("customerTransactionMember", new Class<?>[]{TransactionRawEvent.class}, existing);
        assertThat(redisTemplate.opsForZSet().score(customerTransactionsKey, existingMember))
                .isNotNull();
    }

    @Test
    void missingActiveCommitmentHistoryCannotBeUsedByANewAttestedSnapshot() {
        FeatureNamespace namespace = attestedNamespace();
        TransactionRawEvent existing = event("expired-history-marker", CURRENT_TIMESTAMP, BigDecimal.ONE, "PLN");
        TransactionEnrichedEvent existingEnriched = enriched(existing);
        featureStore.recordAttestedTransaction(existingEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), existingEnriched));
        String processedKey = redisTemplate.keys("feature:v3:attested:*").stream()
                .filter(key -> key.endsWith(":customer:processed-transactions")).findFirst().orElseThrow();
        redisTemplate.delete(processedKey);

        TransactionRawEvent next = event("after-history-loss", CURRENT_TIMESTAMP.plus(Duration.ofDays(1)), BigDecimal.ONE, "PLN");
        assertThatThrownBy(() -> featureStore.loadSnapshot(next, namespace))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
    }

    private FeatureNamespace attestedNamespace() {
        SourceScope scope = mock(SourceScope.class);
        when(scope.issuer()).thenReturn("issuer");
        when(scope.institution()).thenReturn("institution");
        when(scope.source()).thenReturn("source");
        when(scope.sourceEpoch()).thenReturn("epoch");
        SourceLineage authority = mock(SourceLineage.class);
        when(authority.state()).thenReturn(SourceLineage.State.VERIFIED);
        when(authority.source()).thenReturn(scope);
        when(authority.receiptId()).thenReturn("a".repeat(64));
        return FeatureNamespace.attested(authority);
    }

    private TransactionEnrichedEvent enriched(TransactionRawEvent raw) {
        var snapshot = new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
        var features = new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw, snapshot);
        return new TransactionEnrichedEventMapper().toAttestedEvent(raw, features);
    }

    private RedisFeatureStore pauseAfterZSetRange(String key, CountDownLatch paused, CountDownLatch release) {
        StringRedisTemplate readerTemplate = spy(redisTemplate);
        ZSetOperations<String, String> zSetOperations = spy(redisTemplate.opsForZSet());
        doReturn(zSetOperations).when(readerTemplate).opsForZSet();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            paused.countDown();
            await(release);
            return result;
        }).when(zSetOperations).rangeByScoreWithScores(eq(key), anyDouble(), anyDouble(), anyLong(), anyLong());
        return new RedisFeatureStore(readerTemplate,
                new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofDays(8),
                        Duration.ofDays(180), Duration.ofDays(30)),
                new CurrencyAmountConverter());
    }

    private String privateKey(String methodName, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = RedisFeatureStore.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return (String) method.invoke(featureStore, arguments);
    }

    private TransactionRawEvent withDevice(TransactionRawEvent raw, String deviceId) {
        DeviceInfo device = raw.deviceInfo();
        DeviceInfo replacement = new DeviceInfo(deviceId, device.fingerprint(), device.ipAddress(), device.userAgent(),
                device.platform(), device.browser(), device.trustedDevice(), device.proxyDetected(), device.vpnDetected(),
                device.attributes());
        return new TransactionRawEvent(raw.eventId(), raw.transactionId(), raw.correlationId(), raw.customerId(),
                raw.accountId(), raw.paymentInstrumentId(), raw.createdAt(), raw.transactionTimestamp(),
                raw.transactionAmount(), raw.merchantInfo(), replacement, raw.locationInfo(), raw.customerContext(),
                raw.transactionType(), raw.authorizationMethod(), raw.sourceSystem(), raw.traceId(), raw.attributes());
    }

    private static String redisHashTag(String key) {
        int open = key.indexOf('{');
        int close = key.indexOf('}', open + 1);
        return open < 0 || close <= open ? "MISSING" : key.substring(open + 1, close);
    }

    private void assertMalformed(TransactionRawEvent current, List<String> members) {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        for (int index = 0; index < members.size(); index++) {
            redisTemplate.opsForZSet().add(
                    customerTransactionsKey(),
                    members.get(index),
                    score(CURRENT_TIMESTAMP.minusSeconds(10).plusMillis(index))
            );
        }

        assertThatThrownBy(() -> featureStore.loadSnapshot(current))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasRootCauseMessage("MALFORMED_RECENT_TRANSACTION_MEMBERSHIP");
    }

    private void assertConsistent(
            FeatureStoreSnapshot snapshot,
            int count,
            BigDecimal sum,
            List<String> transactionIds
    ) {
        assertThat(snapshot.recentTransactionCount()).isEqualTo(count);
        assertThat(snapshot.recentAmountSumPln()).isEqualByComparingTo(sum);
        assertThat(snapshot.recentTransactions()).extracting("transactionId").containsExactlyElementsOf(transactionIds);
    }

    private BigDecimal sum(FeatureStoreSnapshot snapshot) {
        return snapshot.recentTransactions().stream()
                .map(transaction -> transaction.amountPln())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private TransactionRawEvent event(String transactionId, Instant timestamp, BigDecimal amount, String currency) {
        TransactionRawEvent base = TransactionFixtures.rawTransaction().build();
        return new TransactionRawEvent(
                "event-" + transactionId,
                transactionId,
                "correlation-" + transactionId,
                CUSTOMER_ID,
                base.accountId(),
                base.paymentInstrumentId(),
                timestamp.plusSeconds(1),
                timestamp,
                new Money(amount, currency),
                base.merchantInfo(),
                base.deviceInfo(),
                base.locationInfo(),
                base.customerContext(),
                base.transactionType(),
                base.authorizationMethod(),
                base.sourceSystem(),
                base.traceId(),
                base.attributes()
        );
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String customerTransactionsKey() {
        return "feature:customer:" + CUSTOMER_ID + ":transactions";
    }

    private double score(Instant timestamp) {
        return timestamp.toEpochMilli();
    }
}
