package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.model.Money;
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
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        featureStore.recordAttestedTransaction(olderEnriched, namespace,
                SourceEventCommitment.attestedHistory("a".repeat(64), olderEnriched));
        assertThat(featureStore.loadSnapshot(next, namespace).lastTransactionTimestamp()).isEqualTo(CURRENT_TIMESTAMP);

        Set<String> keys = redisTemplate.keys("feature:v3:attested:*");
        assertThat(keys).hasSize(5);
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
