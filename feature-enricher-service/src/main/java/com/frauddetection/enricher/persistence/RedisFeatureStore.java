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
        try {
            Boolean recorded = stringRedisTemplate.opsForSet().isMember(
                    processedTransactionsKey(event.customerId()),
                    event.transactionId()
            );
            return Boolean.TRUE.equals(recorded);
        } catch (Exception exception) {
            throw new FeatureEnrichmentException("Failed to check transaction idempotency state in Redis.", exception);
        }
    }

    @Override
    public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) {
        try {
            String customerTransactionsKey = customerTransactionsKey(event.customerId());
            String merchantTransactionsKey = merchantTransactionsKey(event.customerId(), event.merchantInfo().merchantId());
            String deviceSetKey = knownDevicesKey(event.customerId());
            String lastTransactionKey = lastTransactionKey(event.customerId());

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
        try {
            double transactionScore = scoreFor(event.transactionTimestamp());
            stringRedisTemplate.opsForZSet().add(
                    customerTransactionsKey(event.customerId()),
                    customerTransactionMember(event),
                    transactionScore
            );
            stringRedisTemplate.opsForZSet().add(
                    merchantTransactionsKey(event.customerId(), event.merchantInfo().merchantId()),
                    event.transactionId(),
                    transactionScore
            );
            stringRedisTemplate.opsForSet().add(knownDevicesKey(event.customerId()), event.deviceInfo().deviceId());
            stringRedisTemplate.opsForSet().add(processedTransactionsKey(event.customerId()), event.transactionId());
            stringRedisTemplate.opsForValue().set(lastTransactionKey(event.customerId()), event.transactionTimestamp().toString());

            stringRedisTemplate.expire(customerTransactionsKey(event.customerId()), featureStoreProperties.transactionKeyTtl());
            stringRedisTemplate.expire(
                    merchantTransactionsKey(event.customerId(), event.merchantInfo().merchantId()),
                    featureStoreProperties.transactionKeyTtl()
            );
            stringRedisTemplate.expire(knownDevicesKey(event.customerId()), featureStoreProperties.knownDeviceTtl());
            stringRedisTemplate.expire(processedTransactionsKey(event.customerId()), featureStoreProperties.transactionKeyTtl());
            stringRedisTemplate.expire(lastTransactionKey(event.customerId()), featureStoreProperties.lastTransactionTtl());
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

    private String customerTransactionsKey(String customerId) {
        return "feature:customer:" + customerId + ":transactions";
    }

    private String customerTransactionMember(TransactionRawEvent event) {
        BigDecimal amountPln = currencyAmountConverter.toPln(event.transactionAmount().amount(), event.transactionAmount().currency());
        return event.transactionId()
                + "|" + event.transactionAmount().amount()
                + "|" + event.transactionAmount().currency()
                + "|" + amountPln;
    }

    private String merchantTransactionsKey(String customerId, String merchantId) {
        return "feature:customer:" + customerId + ":merchant:" + merchantId + ":transactions";
    }

    private String knownDevicesKey(String customerId) {
        return "feature:customer:" + customerId + ":devices";
    }

    private String processedTransactionsKey(String customerId) {
        return "feature:customer:" + customerId + ":processed-transactions";
    }

    private String lastTransactionKey(String customerId) {
        return "feature:customer:" + customerId + ":last-transaction";
    }
}
