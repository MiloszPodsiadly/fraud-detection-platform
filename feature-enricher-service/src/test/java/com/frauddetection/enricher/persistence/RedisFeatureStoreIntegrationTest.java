package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
