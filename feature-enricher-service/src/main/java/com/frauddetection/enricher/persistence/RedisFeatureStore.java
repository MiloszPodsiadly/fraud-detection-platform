package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.domain.RecentTransaction;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class RedisFeatureStore implements FeatureStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final FeatureStoreProperties featureStoreProperties;
    private final CurrencyAmountConverter currencyAmountConverter;
    private final int maxFinalRecentTransactionCount;

    @Autowired
    public RedisFeatureStore(
            StringRedisTemplate stringRedisTemplate,
            FeatureStoreProperties featureStoreProperties,
            CurrencyAmountConverter currencyAmountConverter
    ) {
        this(
                stringRedisTemplate,
                featureStoreProperties,
                currencyAmountConverter,
                FraudFeatureValueBoundsContract.MAX_RECENT_TRANSACTION_COUNT
        );
    }

    RedisFeatureStore(
            StringRedisTemplate stringRedisTemplate,
            FeatureStoreProperties featureStoreProperties,
            CurrencyAmountConverter currencyAmountConverter,
            int maxFinalRecentTransactionCount
    ) {
        if (maxFinalRecentTransactionCount < 1) {
            throw new IllegalArgumentException("maxFinalRecentTransactionCount must be positive");
        }
        this.stringRedisTemplate = stringRedisTemplate;
        this.featureStoreProperties = featureStoreProperties;
        this.currencyAmountConverter = currencyAmountConverter;
        this.maxFinalRecentTransactionCount = maxFinalRecentTransactionCount;
    }

    @Override
    public boolean hasRecordedTransaction(TransactionRawEvent event) {
        return hasRecordedTransaction(event, FeatureNamespace.legacy());
    }

    @Override
    public boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
        try {
            String key = processedTransactionsKey(namespace, event.customerId());
            Boolean recorded = stringRedisTemplate.opsForSet().isMember(key, event.transactionId());
            if (Boolean.TRUE.equals(recorded)
                    && !Boolean.TRUE.equals(stringRedisTemplate.expire(key, featureStoreProperties.transactionKeyTtl()))) {
                throw new IllegalStateException("PROCESSED_TRANSACTION_TTL_UNRESOLVED");
            }
            return Boolean.TRUE.equals(recorded);
        } catch (Exception exception) {
            throw new FeatureEnrichmentException("Failed to check transaction idempotency state in Redis.", exception);
        }
    }

    @Override
    public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) {
        return loadSnapshot(event, FeatureNamespace.legacy());
    }

    @Override
    public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
        try {
            String customerTransactionsKey = customerTransactionsKey(namespace, event.customerId());
            String merchantTransactionsKey = merchantTransactionsKey(namespace, event.customerId(), event.merchantInfo().merchantId());
            String deviceSetKey = knownDevicesKey(namespace, event.customerId());
            String lastTransactionKey = lastTransactionKey(namespace, event.customerId());

            double recentWindowStart = scoreFor(event.transactionTimestamp().minus(featureStoreProperties.recentTransactionWindow()));
            double merchantWindowStart = scoreFor(event.transactionTimestamp().minus(featureStoreProperties.merchantFrequencyWindow()));
            double eventTimestamp = scoreFor(event.transactionTimestamp());

            stringRedisTemplate.opsForZSet().removeRangeByScore(customerTransactionsKey, Double.NEGATIVE_INFINITY, recentWindowStart);
            stringRedisTemplate.opsForZSet().removeRangeByScore(merchantTransactionsKey, Double.NEGATIVE_INFINITY, merchantWindowStart);

            Set<ZSetOperations.TypedTuple<String>> recentTransactionEntries =
                    stringRedisTemplate.opsForZSet().rangeByScoreWithScores(
                            customerTransactionsKey,
                            recentWindowStart,
                            eventTimestamp,
                            0,
                            maxFinalRecentTransactionCount
                    );
            if (recentTransactionEntries != null
                    && recentTransactionEntries.size() >= maxFinalRecentTransactionCount) {
                throw new IllegalStateException("RECENT_TRANSACTION_WINDOW_CAPACITY_EXCEEDED");
            }
            List<RecentTransaction> recentTransactions = parseTransactions(recentTransactionEntries);
            Long merchantFrequency = stringRedisTemplate.opsForZSet().count(merchantTransactionsKey, merchantWindowStart, eventTimestamp);
            Boolean knownDevice = stringRedisTemplate.opsForSet().isMember(deviceSetKey, event.deviceInfo().deviceId());
            String lastTransactionTimestamp = stringRedisTemplate.opsForValue().get(lastTransactionKey);

            return new FeatureStoreSnapshot(
                    recentTransactions.size(),
                    sumAmountsPln(recentTransactions),
                    recentTransactions,
                    merchantFrequency == null ? 0 : merchantFrequency.intValue(),
                    lastTransactionTimestamp == null ? null : Instant.parse(lastTransactionTimestamp),
                    Boolean.TRUE.equals(knownDevice)
            );
        } catch (Exception exception) {
            throw new FeatureEnrichmentException("Failed to load feature snapshot from Redis.", exception);
        }
    }

    @Override
    public void recordTransaction(TransactionRawEvent event) {
        recordTransaction(event, FeatureNamespace.legacy());
    }

    @Override
    public void recordTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
        try {
            double transactionScore = scoreFor(event.transactionTimestamp());
            String customerTransactionsKey = customerTransactionsKey(namespace, event.customerId());
            String merchantTransactionsKey = merchantTransactionsKey(namespace, event.customerId(), event.merchantInfo().merchantId());
            String knownDevicesKey = knownDevicesKey(namespace, event.customerId());
            String processedTransactionsKey = processedTransactionsKey(namespace, event.customerId());
            String lastTransactionKey = lastTransactionKey(namespace, event.customerId());
            stringRedisTemplate.opsForZSet().add(
                    customerTransactionsKey,
                    customerTransactionMember(event),
                    transactionScore
            );
            stringRedisTemplate.opsForZSet().add(
                    merchantTransactionsKey,
                    event.transactionId(),
                    transactionScore
            );
            stringRedisTemplate.opsForSet().add(knownDevicesKey, event.deviceInfo().deviceId());
            stringRedisTemplate.opsForValue().set(lastTransactionKey, event.transactionTimestamp().toString());

            stringRedisTemplate.expire(customerTransactionsKey, featureStoreProperties.transactionKeyTtl());
            stringRedisTemplate.expire(merchantTransactionsKey, featureStoreProperties.transactionKeyTtl());
            stringRedisTemplate.expire(knownDevicesKey, featureStoreProperties.knownDeviceTtl());
            stringRedisTemplate.expire(lastTransactionKey, featureStoreProperties.lastTransactionTtl());
            // Write the idempotency marker last so retries can complete any partial feature-state update.
            stringRedisTemplate.opsForSet().add(processedTransactionsKey, event.transactionId());
            stringRedisTemplate.expire(processedTransactionsKey, featureStoreProperties.transactionKeyTtl());
        } catch (Exception exception) {
            throw new FeatureEnrichmentException("Failed to persist feature snapshot to Redis.", exception);
        }
    }

    private BigDecimal sumAmountsPln(List<RecentTransaction> transactions) {
        BigDecimal total = BigDecimal.ZERO;
        for (RecentTransaction transaction : transactions) {
            total = total.add(transaction.amountPln());
        }
        return total;
    }

    private List<RecentTransaction> parseTransactions(Set<ZSetOperations.TypedTuple<String>> entries) {
        if (entries == null) {
            throw malformedRecentTransactionMembership();
        }
        if (entries.isEmpty()) {
            return List.of();
        }

        List<RecentTransaction> transactions = new ArrayList<>();
        Set<String> transactionIds = new HashSet<>();
        for (ZSetOperations.TypedTuple<String> entry : entries) {
            if (entry == null || entry.getValue() == null || entry.getScore() == null) {
                throw malformedRecentTransactionMembership();
            }
            String[] parts = entry.getValue().split("\\|", -1);
            if (parts.length != 4
                    || parts[0].isBlank()
                    || !parts[0].equals(parts[0].trim())
                    || !transactionIds.add(parts[0])
                    || !Double.isFinite(entry.getScore())
                    || entry.getScore() != Math.rint(entry.getScore())) {
                throw malformedRecentTransactionMembership();
            }
            BigDecimal amount = new BigDecimal(parts[1]);
            String currency = parts[2];
            BigDecimal amountPln = new BigDecimal(parts[3]);
            BigDecimal expectedAmountPln = currencyAmountConverter.toPln(amount, currency);
            if (amount.signum() <= 0
                    || amountPln.signum() <= 0
                    || !currency.equals(currency.toUpperCase(java.util.Locale.ROOT))
                    || amountPln.compareTo(expectedAmountPln) != 0) {
                throw malformedRecentTransactionMembership();
            }
            transactions.add(new RecentTransaction(
                    parts[0],
                    Instant.ofEpochMilli(entry.getScore().longValue()),
                    amount,
                    currency,
                    amountPln
            ));
        }
        return List.copyOf(transactions);
    }

    private IllegalStateException malformedRecentTransactionMembership() {
        return new IllegalStateException("MALFORMED_RECENT_TRANSACTION_MEMBERSHIP");
    }

    private double scoreFor(Instant instant) {
        return instant.toEpochMilli();
    }

    private String customerTransactionsKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace) + "customer:" + customerId + ":transactions";
    }

    private String customerTransactionMember(TransactionRawEvent event) {
        BigDecimal amountPln = currencyAmountConverter.toPln(event.transactionAmount().amount(), event.transactionAmount().currency());
        return event.transactionId()
                + "|" + event.transactionAmount().amount()
                + "|" + event.transactionAmount().currency()
                + "|" + amountPln;
    }

    private String merchantTransactionsKey(FeatureNamespace namespace, String customerId, String merchantId) {
        return namespacePrefix(namespace) + "customer:" + customerId + ":merchant:" + merchantId + ":transactions";
    }

    private String knownDevicesKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace) + "customer:" + customerId + ":devices";
    }

    private String processedTransactionsKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace) + "customer:" + customerId + ":processed-transactions";
    }

    private String lastTransactionKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace) + "customer:" + customerId + ":last-transaction";
    }

    private String namespacePrefix(FeatureNamespace namespace) {
        if (namespace == null) { throw new IllegalArgumentException("FEATURE_NAMESPACE_REQUIRED"); }
        return namespace.isLegacy() ? "feature:" : "feature:v2:attested:" + namespace.keySegment() + ":";
    }
}
