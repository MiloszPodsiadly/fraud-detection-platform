package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.domain.RecentTransaction;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class RedisFeatureStore implements FeatureStore {

    private static final DefaultRedisScript<Long> ATTESTED_HISTORY_SCRIPT = new DefaultRedisScript<>("""
            local expected = {'zset', 'zset', 'set', 'hash', 'string', 'set'}
            for i = 1, 6 do
              local actual = redis.call('TYPE', KEYS[i]).ok
              if actual ~= 'none' and actual ~= expected[i] then return -3 end
            end
            local tx = ARGV[1]
            local commitment = ARGV[2]
            local member = ARGV[3]
            local score = tonumber(ARGV[4])
            local device = ARGV[5]
            local storedLastValue = redis.call('GET', KEYS[5])
            local storedLast = tonumber(storedLastValue)
            if storedLastValue and not storedLast then return -2 end
            local existing = redis.call('HGET', KEYS[4], tx)
            if existing then
              if existing ~= commitment then return -1 end
              local customerScore = redis.call('ZSCORE', KEYS[1], member)
              local merchantScore = redis.call('ZSCORE', KEYS[2], tx)
              local last = tonumber(redis.call('GET', KEYS[5]))
              local recentWindowMillis = tonumber(ARGV[12])
              local merchantWindowMillis = tonumber(ARGV[13])
              local customerPruned = not customerScore and last and last >= score + recentWindowMillis
              local merchantPruned = not merchantScore and last and last >= score + merchantWindowMillis
              if redis.call('PTTL', KEYS[6]) <= 0 or redis.call('SISMEMBER', KEYS[6], tx) == 0 then return -2 end
              for i = 1, 5 do
                local ttl = redis.call('PTTL', KEYS[i])
                local naturallyPruned = (i == 1 and customerPruned) or (i == 2 and merchantPruned)
                if naturallyPruned then
                  if ttl == -1 or ttl == 0 then return -2 end
                elseif ttl <= 0 then
                  return -2
                end
              end
              if (not customerScore and not customerPruned) or (customerScore and tonumber(customerScore) ~= score)
                    or (not merchantScore and not merchantPruned) or (merchantScore and tonumber(merchantScore) ~= score)
                    or redis.call('SISMEMBER', KEYS[3], device) == 0 or not last or last < score then return -2 end
              if ARGV[14] == '1' then return 0 end
              redis.call('SADD', KEYS[6], tx)
              redis.call('PEXPIRE', KEYS[1], ARGV[7])
              redis.call('PEXPIRE', KEYS[2], ARGV[7])
              redis.call('PEXPIRE', KEYS[3], ARGV[8])
              redis.call('PEXPIRE', KEYS[4], ARGV[9])
              redis.call('PEXPIRE', KEYS[5], ARGV[10])
              redis.call('PEXPIRE', KEYS[6], ARGV[11])
              return 0
            end
            if ARGV[14] == '1' then return -2 end
            local oldCustomerScore = redis.call('ZSCORE', KEYS[1], member)
            local oldMerchantScore = redis.call('ZSCORE', KEYS[2], tx)
            if redis.call('SISMEMBER', KEYS[6], tx) == 1 or oldCustomerScore or oldMerchantScore then
              return -2
            end
            redis.call('ZADD', KEYS[1], score, member)
            redis.call('ZADD', KEYS[2], score, tx)
            redis.call('SADD', KEYS[3], device)
            redis.call('HSET', KEYS[4], tx, commitment)
            redis.call('SADD', KEYS[6], tx)
            if not storedLast or score > storedLast then redis.call('SET', KEYS[5], tostring(score)) end
            redis.call('PEXPIRE', KEYS[1], ARGV[7])
            redis.call('PEXPIRE', KEYS[2], ARGV[7])
            redis.call('PEXPIRE', KEYS[3], ARGV[8])
            redis.call('PEXPIRE', KEYS[4], ARGV[9])
            redis.call('PEXPIRE', KEYS[5], ARGV[10])
            redis.call('PEXPIRE', KEYS[6], ARGV[11])
            return 1
            """, Long.class);

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
            Boolean recorded = namespace.isLegacy()
                    ? stringRedisTemplate.opsForSet().isMember(key, event.transactionId())
                    : stringRedisTemplate.opsForHash().hasKey(key, event.transactionId());
            if (Boolean.TRUE.equals(recorded) && namespace.isLegacy()
                    && !Boolean.TRUE.equals(stringRedisTemplate.expire(key, featureStoreProperties.transactionKeyTtl()))) {
                throw new IllegalStateException("PROCESSED_TRANSACTION_TTL_UNRESOLVED");
            }
            if (Boolean.TRUE.equals(recorded)) { return true; }
            if (namespace.isLegacy()) { return false; }
            BigDecimal amountPln = currencyAmountConverter.toPln(event.transactionAmount().amount(),
                    event.transactionAmount().currency());
            String member = event.transactionId() + "|" + event.transactionAmount().amount() + "|"
                    + event.transactionAmount().currency() + "|" + amountPln;
            boolean survivingMembership = Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(attestedTransactionIdsKey(namespace, event.customerId()), event.transactionId()))
                    || stringRedisTemplate.opsForZSet().score(customerTransactionsKey(namespace, event.customerId()), member) != null
                    || stringRedisTemplate.opsForZSet().score(merchantTransactionsKey(namespace, event.customerId(),
                            event.merchantInfo().merchantId()), event.transactionId()) != null
                    || Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                            .isMember(v2ProcessedTransactionsKey(namespace, event.customerId()), event.transactionId()))
                    || stringRedisTemplate.opsForZSet().score(v2CustomerTransactionsKey(namespace, event.customerId()), member) != null
                    || stringRedisTemplate.opsForZSet().score(v2MerchantTransactionsKey(namespace, event.customerId(),
                            event.merchantInfo().merchantId()), event.transactionId()) != null;
            if (survivingMembership) { throw new FeatureEnrichmentException("FEATURE_HISTORY_REBUILD_REQUIRED"); }
            return false;
        } catch (FeatureEnrichmentException known) {
            throw known;
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
            if (!namespace.isLegacy()) {
                stringRedisTemplate.opsForZSet().removeRangeByScore(v2CustomerTransactionsKey(namespace, event.customerId()),
                        Double.NEGATIVE_INFINITY, recentWindowStart);
                stringRedisTemplate.opsForZSet().removeRangeByScore(v2MerchantTransactionsKey(namespace, event.customerId(),
                        event.merchantInfo().merchantId()), Double.NEGATIVE_INFINITY, merchantWindowStart);
            }

            Set<ZSetOperations.TypedTuple<String>> recentTransactionEntries =
                    stringRedisTemplate.opsForZSet().rangeByScoreWithScores(
                            customerTransactionsKey,
                            recentWindowStart,
                            eventTimestamp,
                            0,
                            maxFinalRecentTransactionCount
                    );
            Set<ZSetOperations.TypedTuple<String>> legacyRecentEntries = namespace.isLegacy() ? Set.of()
                    : stringRedisTemplate.opsForZSet().rangeByScoreWithScores(
                            v2CustomerTransactionsKey(namespace, event.customerId()), recentWindowStart,
                            eventTimestamp, 0, maxFinalRecentTransactionCount);
            List<RecentTransaction> recentTransactions = mergeRecentTransactions(
                    parseTransactions(recentTransactionEntries), parseTransactions(legacyRecentEntries));
            if (recentTransactions.size() >= maxFinalRecentTransactionCount) {
                throw new IllegalStateException("RECENT_TRANSACTION_WINDOW_CAPACITY_EXCEEDED");
            }
            Long merchantFrequency;
            if (namespace.isLegacy()) {
                merchantFrequency = stringRedisTemplate.opsForZSet().count(merchantTransactionsKey, merchantWindowStart, eventTimestamp);
            } else {
                Map<String, Double> merchantTransactions = new HashMap<>();
                mergeMerchantTransactions(merchantTransactions,
                        boundedMerchantEntries(merchantTransactionsKey, merchantWindowStart, eventTimestamp));
                mergeMerchantTransactions(merchantTransactions, boundedMerchantEntries(
                        v2MerchantTransactionsKey(namespace, event.customerId(), event.merchantInfo().merchantId()),
                        merchantWindowStart, eventTimestamp));
                merchantFrequency = (long) merchantTransactions.size();
            }
            if (merchantFrequency != null
                    && merchantFrequency >= FraudFeatureValueBoundsContract.MAX_MERCHANT_FREQUENCY_7D) {
                throw new IllegalStateException("MERCHANT_HISTORY_CAPACITY_EXCEEDED");
            }
            Boolean knownDevice = stringRedisTemplate.opsForSet().isMember(deviceSetKey, event.deviceInfo().deviceId());
            if (!namespace.isLegacy() && !Boolean.TRUE.equals(knownDevice)) {
                knownDevice = stringRedisTemplate.opsForSet().isMember(v2KnownDevicesKey(namespace, event.customerId()), event.deviceInfo().deviceId());
            }
            String lastTransactionTimestamp = stringRedisTemplate.opsForValue().get(lastTransactionKey);
            if (!namespace.isLegacy()) {
                String oldTimestamp = stringRedisTemplate.opsForValue().get(v2LastTransactionKey(namespace, event.customerId()));
                Instant current = parseAttestedLastTransaction(lastTransactionTimestamp);
                Instant old = oldTimestamp == null ? null : Instant.parse(oldTimestamp);
                if (old != null && (current == null || old.isAfter(current))) { lastTransactionTimestamp = old.toString(); }
                else if (current != null) { lastTransactionTimestamp = current.toString(); }
            }

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
        if (!namespace.isLegacy()) {
            throw new IllegalArgumentException("ATTESTED_HISTORY_REQUIRES_CANONICAL_ENRICHED_EVENT");
        }
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

    @Override
    public void recordAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace, String historyCommitment) {
        if (event == null || namespace == null || namespace.isLegacy() || historyCommitment == null
                || !historyCommitment.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("ATTESTED_FEATURE_HISTORY_INPUT_INVALID");
        }
        applyAttestedHistory(event.transactionId(), event.customerId(), event.merchantInfo().merchantId(),
                event.deviceInfo().deviceId(), event.transactionTimestamp(), event.transactionAmount().amount(),
                event.transactionAmount().currency(), namespace, historyCommitment, false);
    }

    @Override
    public void verifyAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace, String historyCommitment) {
        if (event == null || namespace == null || namespace.isLegacy() || historyCommitment == null
                || !historyCommitment.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("ATTESTED_FEATURE_HISTORY_INPUT_INVALID");
        }
        applyAttestedHistory(event.transactionId(), event.customerId(), event.merchantInfo().merchantId(),
                event.deviceInfo().deviceId(), event.transactionTimestamp(), event.transactionAmount().amount(),
                event.transactionAmount().currency(), namespace, historyCommitment, true);
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

    private List<RecentTransaction> mergeRecentTransactions(List<RecentTransaction> current, List<RecentTransaction> previous) {
        Map<String, RecentTransaction> byId = new LinkedHashMap<>();
        for (RecentTransaction transaction : current) { byId.put(transaction.transactionId(), transaction); }
        for (RecentTransaction transaction : previous) {
            RecentTransaction existing = byId.putIfAbsent(transaction.transactionId(), transaction);
            if (existing != null && (!existing.transactionTimestamp().equals(transaction.transactionTimestamp())
                    || existing.amount().compareTo(transaction.amount()) != 0
                    || !existing.currency().equals(transaction.currency())
                    || existing.amountPln().compareTo(transaction.amountPln()) != 0)) {
                throw malformedRecentTransactionMembership();
            }
        }
        return byId.values().stream().sorted(java.util.Comparator.comparing(RecentTransaction::transactionTimestamp)).toList();
    }

    private void applyAttestedHistory(String transactionId, String customerId, String merchantId, String deviceId,
                                     Instant timestamp, BigDecimal amount, String currency, FeatureNamespace namespace,
                                     String historyCommitment, boolean mongoAlreadyApplied) {
        try {
            BigDecimal amountPln = currencyAmountConverter.toPln(amount, currency);
            String member = transactionId + "|" + amount + "|" + currency + "|" + amountPln;
            double score = scoreFor(timestamp);
            if (score != Math.rint(score) || !Double.isFinite(score)) {
                throw new FeatureEnrichmentException("FEATURE_HISTORY_TIMESTAMP_INVALID");
            }

            List<String> keys = List.of(customerTransactionsKey(namespace, customerId),
                    merchantTransactionsKey(namespace, customerId, merchantId), knownDevicesKey(namespace, customerId),
                    processedTransactionsKey(namespace, customerId), lastTransactionKey(namespace, customerId),
                    attestedTransactionIdsKey(namespace, customerId));
            if (mongoAlreadyApplied) {
                Long result = executeAttestedHistory(keys, transactionId, historyCommitment, member, score,
                        deviceId, timestamp, true);
                validateAttestedHistoryResult(result);
                if (result != 0) { throw new FeatureEnrichmentException("FEATURE_HISTORY_FINALIZATION_UNRESOLVED"); }
                return;
            }

            // V2 keys were untagged and cannot participate in a Redis Cluster script. Read them during
            // the forward migration window; all new writes use the colocated V3 key family below.
            String previousProcessed = v2ProcessedTransactionsKey(namespace, customerId);
            boolean oldProcessed = Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(previousProcessed, transactionId));
            Double oldCustomerScore = stringRedisTemplate.opsForZSet().score(v2CustomerTransactionsKey(namespace, customerId), member);
            Double oldMerchantScore = stringRedisTemplate.opsForZSet()
                    .score(v2MerchantTransactionsKey(namespace, customerId, merchantId), transactionId);
            if (oldProcessed || oldCustomerScore != null || oldMerchantScore != null) {
                // V2 membership is keyed by business data and is not proof of the receipt-bound replay identity.
                throw new FeatureEnrichmentException(oldMerchantScore != null && oldCustomerScore == null
                        ? "FEATURE_HISTORY_COMMITMENT_CONFLICT" : "FEATURE_HISTORY_REBUILD_REQUIRED");
            }
            Long result = executeAttestedHistory(keys, transactionId, historyCommitment, member, score,
                    deviceId, timestamp, false);
            validateAttestedHistoryResult(result);
        } catch (FeatureEnrichmentException known) {
            throw known;
        } catch (Exception exception) {
            throw new FeatureEnrichmentException("Failed to finalize attested feature history.", exception);
        }
    }

    private Long executeAttestedHistory(List<String> keys, String transactionId, String historyCommitment,
                                        String member, double score, String deviceId, Instant timestamp,
                                        boolean verifyOnly) {
        return stringRedisTemplate.execute(ATTESTED_HISTORY_SCRIPT, keys,
                transactionId, historyCommitment, member, Long.toString((long) score), deviceId,
                timestamp.toString(), Long.toString(featureStoreProperties.transactionKeyTtl().toMillis()),
                Long.toString(featureStoreProperties.knownDeviceTtl().toMillis()),
                Long.toString(featureStoreProperties.transactionKeyTtl().toMillis()),
                Long.toString(featureStoreProperties.lastTransactionTtl().toMillis()),
                Long.toString(featureStoreProperties.transactionKeyTtl().toMillis()),
                Long.toString(featureStoreProperties.recentTransactionWindow().toMillis()),
                Long.toString(featureStoreProperties.merchantFrequencyWindow().toMillis()),
                verifyOnly ? "1" : "0");
    }

    private void validateAttestedHistoryResult(Long result) {
            if (result == null) { throw new FeatureEnrichmentException("FEATURE_HISTORY_FINALIZATION_UNRESOLVED"); }
            if (result == -1) { throw new FeatureEnrichmentException("FEATURE_HISTORY_COMMITMENT_CONFLICT"); }
            if (result == -2) { throw new FeatureEnrichmentException("FEATURE_HISTORY_REBUILD_REQUIRED"); }
            if (result == -3) { throw new FeatureEnrichmentException("FEATURE_HISTORY_REDIS_SCHEMA_CONFLICT"); }
            if (result < 0 || result > 1) { throw new FeatureEnrichmentException("FEATURE_HISTORY_FINALIZATION_UNRESOLVED"); }
    }

    private static Instant parseAttestedLastTransaction(String value) {
        if (value == null) { return null; }
        try { return Instant.ofEpochMilli(Long.parseLong(value)); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("MALFORMED_LAST_TRANSACTION_TIMESTAMP"); }
    }

    private static String digest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("FEATURE_NAMESPACE_HASH_UNAVAILABLE");
        }
    }

    private double scoreFor(Instant instant) {
        return instant.toEpochMilli();
    }

    private String customerTransactionsKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace, customerId) + (namespace.isLegacy()
                ? "customer:" + customerId + ":transactions" : "customer:transactions");
    }

    private String customerTransactionMember(TransactionRawEvent event) {
        BigDecimal amountPln = currencyAmountConverter.toPln(event.transactionAmount().amount(), event.transactionAmount().currency());
        return event.transactionId()
                + "|" + event.transactionAmount().amount()
                + "|" + event.transactionAmount().currency()
                + "|" + amountPln;
    }

    private String merchantTransactionsKey(FeatureNamespace namespace, String customerId, String merchantId) {
        return namespacePrefix(namespace, customerId) + (namespace.isLegacy()
                ? "customer:" + customerId + ":merchant:" + merchantId + ":transactions"
                : "merchant:" + digest("feature-merchant/v3", merchantId) + ":transactions");
    }

    private String knownDevicesKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace, customerId) + (namespace.isLegacy() ? "customer:" + customerId + ":devices" : "customer:devices");
    }

    private String processedTransactionsKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace, customerId) + (namespace.isLegacy()
                ? "customer:" + customerId + ":processed-transactions" : "customer:processed-transactions");
    }

    private String lastTransactionKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace, customerId) + (namespace.isLegacy()
                ? "customer:" + customerId + ":last-transaction" : "customer:last-transaction");
    }

    private String attestedTransactionIdsKey(FeatureNamespace namespace, String customerId) {
        return namespacePrefix(namespace, customerId) + "customer:transaction-ids";
    }

    private Set<ZSetOperations.TypedTuple<String>> boundedMerchantEntries(String key, double start, double end) {
        Set<ZSetOperations.TypedTuple<String>> entries = stringRedisTemplate.opsForZSet().rangeByScoreWithScores(
                key, start, end, 0, FraudFeatureValueBoundsContract.MAX_MERCHANT_FREQUENCY_7D);
        if (entries == null) { throw new IllegalStateException("MERCHANT_HISTORY_READ_UNRESOLVED"); }
        return entries;
    }

    private void mergeMerchantTransactions(Map<String, Double> merged,
                                           Set<ZSetOperations.TypedTuple<String>> entries) {
        for (ZSetOperations.TypedTuple<String> entry : entries) {
            if (entry == null || entry.getValue() == null || entry.getValue().isBlank()
                    || !entry.getValue().equals(entry.getValue().trim()) || entry.getScore() == null
                    || !Double.isFinite(entry.getScore()) || entry.getScore() != Math.rint(entry.getScore())) {
                throw new IllegalStateException("MALFORMED_MERCHANT_TRANSACTION_MEMBERSHIP");
            }
            Double priorScore = merged.putIfAbsent(entry.getValue(), entry.getScore());
            if (priorScore != null && Double.compare(priorScore, entry.getScore()) != 0) {
                throw new IllegalStateException("MALFORMED_MERCHANT_TRANSACTION_MEMBERSHIP");
            }
            if (merged.size() >= FraudFeatureValueBoundsContract.MAX_MERCHANT_FREQUENCY_7D) {
                throw new IllegalStateException("MERCHANT_HISTORY_CAPACITY_EXCEEDED");
            }
        }
    }

    private String namespacePrefix(FeatureNamespace namespace, String customerId) {
        if (namespace == null) { throw new IllegalArgumentException("FEATURE_NAMESPACE_REQUIRED"); }
        if (namespace.isLegacy()) { return "feature:"; }
        return "feature:v3:attested:{" + digest("feature-customer-slot/v3", namespace.keySegment(), customerId) + "}:";
    }

    private String v2CustomerTransactionsKey(FeatureNamespace namespace, String customerId) {
        return v2Prefix(namespace, customerId) + ":transactions";
    }
    private String v2MerchantTransactionsKey(FeatureNamespace namespace, String customerId, String merchantId) {
        return v2Prefix(namespace, customerId) + ":merchant:" + merchantId + ":transactions";
    }
    private String v2KnownDevicesKey(FeatureNamespace namespace, String customerId) {
        return v2Prefix(namespace, customerId) + ":devices";
    }
    private String v2ProcessedTransactionsKey(FeatureNamespace namespace, String customerId) {
        return v2Prefix(namespace, customerId) + ":processed-transactions";
    }
    private String v2LastTransactionKey(FeatureNamespace namespace, String customerId) {
        return v2Prefix(namespace, customerId) + ":last-transaction";
    }
    private String v2Prefix(FeatureNamespace namespace, String customerId) {
        return "feature:v2:attested:" + namespace.keySegment() + ":customer:" + customerId;
    }
}
