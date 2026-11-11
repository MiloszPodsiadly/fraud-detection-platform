package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.service.CurrencyAmountConverter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explicitly run with -Dmerchant.history.benchmark=true; excluded from routine CI workload. */
@EnabledIfSystemProperty(named = "merchant.history.benchmark", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisMerchantHistoryBenchmarkTest {
    private static final Instant EVENT_TIME = Instant.parse("2026-04-20T10:15:28Z");
    private static final String CUSTOMER = "merchant-benchmark-customer";
    private static final String MERCHANT = "merchant-benchmark-id";
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURED_ITERATIONS = 8;
    private static final int SEED_BATCH_SIZE = 10_000;
    private static final long REDIS_MEMORY_LIMIT_BYTES = 1_073_741_824L;
    private static final Pattern CALLS = Pattern.compile("calls=(\\d+)");

    private GenericContainer<?> redisContainer;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisFeatureStore store;
    private FeatureNamespace namespace;
    private String v3MerchantKey;
    private String v2MerchantKey;
    private TransactionRawEvent current;
    private com.sun.management.ThreadMXBean allocationBean;

    @BeforeAll
    void startRedis() throws Exception {
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withMemory(REDIS_MEMORY_LIMIT_BYTES).withMemorySwap(REDIS_MEMORY_LIMIT_BYTES));
        redisContainer.start();
        connectionFactory = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        store = new RedisFeatureStore(redis, new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7),
                Duration.ofDays(8), Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
        namespace = namespace();
        v3MerchantKey = privateKey("merchantTransactionsKey", FeatureNamespace.class, String.class, String.class,
                namespace, CUSTOMER, MERCHANT);
        v2MerchantKey = privateKey("v2MerchantTransactionsKey", FeatureNamespace.class, String.class, String.class,
                namespace, CUSTOMER, MERCHANT);
        var base = TransactionFixtures.rawTransaction().build();
        current = new TransactionRawEvent("benchmark-current", "benchmark-current-tx", "benchmark-correlation", CUSTOMER,
                base.accountId(), base.paymentInstrumentId(), EVENT_TIME.plusSeconds(1), EVENT_TIME,
                new com.frauddetection.common.events.model.Money(new BigDecimal("1.00"), "PLN"),
                new com.frauddetection.common.events.model.MerchantInfo(MERCHANT, base.merchantInfo().merchantName(),
                        base.merchantInfo().merchantCategoryCode(), base.merchantInfo().merchantCategory(),
                        base.merchantInfo().acquiringCountryCode(), base.merchantInfo().channel(),
                        base.merchantInfo().cardPresent(), base.merchantInfo().attributes()),
                base.deviceInfo(), base.locationInfo(), base.customerContext(), base.transactionType(),
                base.authorizationMethod(), base.sourceSystem(), base.traceId(), base.attributes());
        allocationBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    }

    @AfterAll
    void stopRedisConnection() {
        if (connectionFactory != null) { connectionFactory.destroy(); }
        if (redisContainer != null) { redisContainer.stop(); }
    }

    @Test
    void recordsRepeatableMerchantHistoryReadMeasurements() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long heapBefore = usedHeap();
        System.out.printf("MERCHANT_BENCHMARK_ENV jdk=%s maxHeapBytes=%d redisImage=%s redisContainerMemoryLimitBytes=%d "
                        + "redisMaxmemory=%s warmup=%d iterations=%d hostMemoryBytes=%d%n",
                System.getProperty("java.version"), Runtime.getRuntime().maxMemory(),
                redisContainer.getDockerImageName(), REDIS_MEMORY_LIMIT_BYTES, redisInfo("memory").getProperty("maxmemory"),
                WARMUP_ITERATIONS, MEASURED_ITERATIONS, os.getTotalMemorySize());

        for (int size : List.of(0, 1_000, 10_000, 100_000)) {
            for (Mode mode : List.of(Mode.V3_ONLY, Mode.V2_ONLY, Mode.DISJOINT, Mode.OVERLAP)) {
                measure(new Scenario(size, mode), WARMUP_ITERATIONS, MEASURED_ITERATIONS, "routine");
            }
        }
        assertMalformedDuplicateFailsClosed();
        System.out.printf("MERCHANT_BENCHMARK_END usedHeapBeforeBytes=%d usedHeapAfterBytes=%d maxMeasuredUnion=100000 "
                        + "highBoundStress=SEPARATELY_GATED routineProfile=true%n", heapBefore, usedHeap());
    }

    @Test
    @EnabledIfSystemProperty(named = "merchant.history.stress", matches = "true")
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void recordsGatedHighBoundHistoryReadMeasurements() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= 4L * 1024 * 1024 * 1024,
                "high-bound profile requires a 4 GiB Java heap");
        Assumptions.assumeTrue(os.getFreeMemorySize() >= 8L * 1024 * 1024 * 1024,
                "high-bound profile requires 8 GiB free host memory");
        System.out.printf("MERCHANT_BENCHMARK_HIGH_BOUND_ENV maxHeapBytes=%d freeHostMemoryBytes=%d redisMemoryLimitBytes=%d "
                        + "timeoutMinutes=20 warmup=%d iterations=%d%n",
                Runtime.getRuntime().maxMemory(), os.getFreeMemorySize(), REDIS_MEMORY_LIMIT_BYTES,
                WARMUP_ITERATIONS, MEASURED_ITERATIONS);
        for (int size : List.of(250_000, 500_000, 1_000_000)) {
            for (Mode mode : List.of(Mode.V3_ONLY, Mode.V2_ONLY, Mode.DISJOINT, Mode.OVERLAP)) {
                measure(new Scenario(size, mode), WARMUP_ITERATIONS, MEASURED_ITERATIONS, "high-bound");
            }
        }
    }

    private void measure(Scenario scenario, int warmups, int iterations, String profile) {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        seed(scenario);
        long expected = scenario.expectedUnionSize();
        for (int index = 0; index < warmups; index++) {
            assertThat(store.loadSnapshot(current, namespace).merchantFrequency7d()).isEqualTo(expected);
        }
        var gcBefore = gcSnapshot();
        Properties beforeCommands = redisInfo("commandstats");
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBytes(threadId);
        long observedHeapPeak = usedHeap();
        List<Long> elapsedNanos = new ArrayList<>(iterations);
        for (int index = 0; index < iterations; index++) {
            long start = System.nanoTime();
            var snapshot = store.loadSnapshot(current, namespace);
            elapsedNanos.add(System.nanoTime() - start);
            assertThat(snapshot.merchantFrequency7d()).isEqualTo(expected);
            observedHeapPeak = Math.max(observedHeapPeak, usedHeap());
        }
        long allocated = allocationBytes(threadId) - allocatedBefore;
        Properties afterCommands = redisInfo("commandstats");
        long commandCount = redisCalls(afterCommands) - redisCalls(beforeCommands)
                - (redisCalls(afterCommands, "info") - redisCalls(beforeCommands, "info"));
        long redisUsedMemory = Long.parseLong(redisInfo("memory").getProperty("used_memory", "0"));
        var gcAfter = gcSnapshot();
        elapsedNanos.sort(Comparator.naturalOrder());
        System.out.printf("MERCHANT_BENCHMARK_RESULT profile=%s samples=%d mode=%s requestedUnion=%d v3=%d v2=%d expected=%d "
                        + "p50Ms=%.3f p95Ms=%.3f maxMs=%.3f allocatedBytesPerRead=%d postReadHeapPeakBytes=%d "
                        + "gcCollectionsDelta=%d gcTimeMsDelta=%d redisCommandsPerRead=%.1f redisUsedMemoryBytes=%d%n",
                profile, iterations, scenario.mode(), scenario.requestedUnion(), scenario.v3Count(), scenario.v2Count(), expected,
                millis(percentile(elapsedNanos, .50)), millis(percentile(elapsedNanos, .95)),
                millis(elapsedNanos.getLast()), allocated / iterations, observedHeapPeak,
                gcAfter.collections() - gcBefore.collections(), gcAfter.timeMillis() - gcBefore.timeMillis(),
                (double) commandCount / iterations, redisUsedMemory);
    }

    private void seed(Scenario scenario) {
        byte[] v3Key = v3MerchantKey.getBytes(StandardCharsets.UTF_8);
        byte[] v2Key = v2MerchantKey.getBytes(StandardCharsets.UTF_8);
        byte[] v3Prefix = (scenario.mode() == Mode.DISJOINT ? "v3-" : "tx-").getBytes(StandardCharsets.UTF_8);
        byte[] v2Prefix = (scenario.mode() == Mode.DISJOINT ? "v2-" : "tx-").getBytes(StandardCharsets.UTF_8);
        long scoreBase = EVENT_TIME.minus(Duration.ofHours(1)).toEpochMilli();
        int batches = Math.max(scenario.v3Count(), scenario.v2Count());
        for (int start = 0; start < batches; start += SEED_BATCH_SIZE) {
            int batchStart = start;
            int batchEnd = Math.min(batches, start + SEED_BATCH_SIZE);
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (int index = batchStart; index < Math.min(batchEnd, scenario.v3Count()); index++) {
                    connection.zAdd(v3Key, scoreBase + index,
                            join(v3Prefix, Integer.toString(index).getBytes(StandardCharsets.UTF_8)));
                }
                for (int index = batchStart; index < Math.min(batchEnd, scenario.v2Count()); index++) {
                    connection.zAdd(v2Key, scoreBase + index,
                            join(v2Prefix, Integer.toString(index).getBytes(StandardCharsets.UTF_8)));
                }
                return null;
            });
        }
    }

    private void assertMalformedDuplicateFailsClosed() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        byte[] sharedId = "conflicting-timestamp".getBytes(StandardCharsets.UTF_8);
        double score = EVENT_TIME.minusSeconds(60).toEpochMilli();
        redis.opsForZSet().add(v3MerchantKey, "conflicting-timestamp", score);
        redis.opsForZSet().add(v2MerchantKey, "conflicting-timestamp", score + 1);
        long start = System.nanoTime();
        assertThatThrownBy(() -> store.loadSnapshot(current, namespace))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasRootCauseMessage("MALFORMED_MERCHANT_TRANSACTION_MEMBERSHIP");
        System.out.printf("MERCHANT_BENCHMARK_MALFORMED conflictingDuplicateReadMs=%.3f result=FAIL_CLOSED%n",
                millis(System.nanoTime() - start));
    }

    private Properties redisInfo(String section) {
        return redis.getConnectionFactory().getConnection().serverCommands().info(section);
    }

    private static long redisCalls(Properties info) {
        long calls = 0;
        for (String property : info.stringPropertyNames()) {
            if (!property.startsWith("cmdstat_")) { continue; }
            calls += redisCalls(info, property.substring("cmdstat_".length()));
        }
        return calls;
    }

    private static long redisCalls(Properties info, String command) {
        String value = info.getProperty("cmdstat_" + command);
        if (value == null) { return 0; }
        Matcher matcher = CALLS.matcher(value);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static long percentile(List<Long> sorted, double quantile) {
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double millis(long nanos) { return nanos / 1_000_000.0d; }

    private static long usedHeap() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap.getUsed();
    }

    private static GcSnapshot gcSnapshot() {
        long collections = 0;
        long time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() >= 0) { collections += bean.getCollectionCount(); }
            if (bean.getCollectionTime() >= 0) { time += bean.getCollectionTime(); }
        }
        return new GcSnapshot(collections, time);
    }

    private long allocationBytes(long threadId) {
        return allocationBean.isThreadAllocatedMemorySupported() && allocationBean.isThreadAllocatedMemoryEnabled()
                ? allocationBean.getThreadAllocatedBytes(threadId) : 0;
    }

    private String privateKey(String methodName, Class<?> first, Class<?> second, Class<?> third,
                              FeatureNamespace featureNamespace, String customer, String merchant) throws Exception {
        Method method = RedisFeatureStore.class.getDeclaredMethod(methodName, first, second, third);
        method.setAccessible(true);
        return (String) method.invoke(store, featureNamespace, customer, merchant);
    }

    private static byte[] join(byte[] prefix, byte[] suffix) {
        byte[] result = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private static FeatureNamespace namespace() {
        SourceScope scope = mock(SourceScope.class);
        when(scope.issuer()).thenReturn("benchmark-issuer");
        when(scope.institution()).thenReturn("benchmark-institution");
        when(scope.source()).thenReturn("benchmark-source");
        when(scope.sourceEpoch()).thenReturn("benchmark-epoch");
        SourceLineage authority = mock(SourceLineage.class);
        when(authority.state()).thenReturn(SourceLineage.State.VERIFIED);
        when(authority.source()).thenReturn(scope);
        return FeatureNamespace.attested(authority);
    }

    private record Scenario(int requestedUnion, Mode mode) {
        int v3Count() {
            return switch (mode) {
                case V3_ONLY, OVERLAP -> requestedUnion;
                case V2_ONLY -> 0;
                case DISJOINT -> (requestedUnion + 1) / 2;
            };
        }
        int v2Count() {
            return switch (mode) {
                case V2_ONLY, OVERLAP -> requestedUnion;
                case V3_ONLY -> 0;
                case DISJOINT -> requestedUnion / 2;
            };
        }
        long expectedUnionSize() { return mode == Mode.OVERLAP ? requestedUnion : (long) v3Count() + v2Count(); }
    }

    private enum Mode { V3_ONLY, V2_ONLY, DISJOINT, OVERLAP }
    private record GcSnapshot(long collections, long timeMillis) { }
}
