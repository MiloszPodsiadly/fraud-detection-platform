package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.domain.RecentTransaction;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class RedisFeatureStore implements FeatureStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final FeatureStoreProperties featureStoreProperties;
    private final CurrencyAmountConverter currencyAmountConverter;

    public RedisFeatureStore(
            StringRedisTemplate stringRedisTemplate,
            FeatureStoreProperties featureStoreProperties,
            CurrencyAmountConverter currencyAmountConverter
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.featureStoreProperties = featureStoreProperties;
        this.currencyAmountConverter = currencyAmountConverter;
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

            Long recentCount = stringRedisTemplate.opsForZSet().count(customerTransactionsKey, recentWindowStart, eventTimestamp);
            Set<ZSetOperations.TypedTuple<String>> recentTransactions =
                    stringRedisTemplate.opsForZSet().rangeByScoreWithScores(customerTransactionsKey, recentWindowStart, eventTimestamp);
            Long merchantFrequency = stringRedisTemplate.opsForZSet().count(merchantTransactionsKey, merchantWindowStart, eventTimestamp);
            Boolean knownDevice = stringRedisTemplate.opsForSet().isMember(deviceSetKey, event.deviceInfo().deviceId());
            String lastTransactionTimestamp = stringRedisTemplate.opsForValue().get(lastTransactionKey);

            return new FeatureStoreSnapshot(
                    recentCount == null ? 0 : recentCount.intValue(),
                    sumAmounts(recentTransactions),
                    sumAmountsPln(recentTransactions),
                    parseTransactions(recentTransactions),
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

    private BigDecimal sumAmounts(Set<ZSetOperations.TypedTuple<String>> entries) {
        if (entries == null || entries.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (ZSetOperations.TypedTuple<String> entry : entries) {
            if (entry == null || entry.getValue() == null) {
                continue;
            }
            String[] parts = entry.getValue().split("\\|");
            if (parts.length >= 2) {
                total = total.add(new BigDecimal(parts[1]));
            }
        }
        return total;
    }

    private BigDecimal sumAmountsPln(Set<ZSetOperations.TypedTuple<String>> entries) {
        BigDecimal total = BigDecimal.ZERO;
        for (RecentTransaction transaction : parseTransactions(entries)) {
            total = total.add(transaction.amountPln());
        }
        return total;
    }

    private List<RecentTransaction> parseTransactions(Set<ZSetOperations.TypedTuple<String>> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<RecentTransaction> transactions = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> entry : entries) {
            if (entry == null || entry.getValue() == null || entry.getScore() == null) {
                continue;
            }
            String[] parts = entry.getValue().split("\\|");
            if (parts.length < 2) {
                continue;
            }
            BigDecimal amount = new BigDecimal(parts[1]);
            String currency = parts.length >= 3 ? parts[2] : "PLN";
            BigDecimal amountPln = parts.length >= 4
                    ? new BigDecimal(parts[3])
                    : currencyAmountConverter.toPln(amount, currency);
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
