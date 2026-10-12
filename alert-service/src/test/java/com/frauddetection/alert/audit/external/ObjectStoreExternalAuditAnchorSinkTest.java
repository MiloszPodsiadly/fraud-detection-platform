package com.frauddetection.alert.audit.external;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.ExternalAuditHeadForensicService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectStoreExternalAuditAnchorSinkTest {

    private static final String ENCODED_PARTITION = "c291cmNlX3NlcnZpY2U6YWxlcnQtc2VydmljZQ";

    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
    private final ObjectMapper canonicalObjectMapper = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build()
            ;
    private final InMemoryObjectStoreAuditAnchorClient client = new InMemoryObjectStoreAuditAnchorClient();
    private final ObjectStoreExternalAuditAnchorSink sink = new ObjectStoreExternalAuditAnchorSink(
            "audit-bucket",
            "audit-anchors",
            client,
            objectMapper
    );

    @Test
    void shouldStoreAnchorUnderDeterministicEncodedObjectKey() {
        ExternalAuditAnchor anchor = ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType());

        sink.publish(anchor);
        String objectKey = "audit-anchors/" + ENCODED_PARTITION + "/00000000000000000001.json";
        String storedJson = new String(client.getObject("audit-bucket", objectKey).orElseThrow(), StandardCharsets.UTF_8);

        assertThat(sink.objectKey("source_service:alert-service", 1L)).isEqualTo(objectKey);
        assertThat(sink.formatChainPosition(1L)).isEqualTo("00000000000000000001");
        assertThat(sink.parseChainPosition(objectKey)).isEqualTo(1L);
        assertThat(storedJson).contains(
                "\"anchor_id\":\"" + anchor.externalAnchorId() + "\"",
                "\"source\":\"object-store\"",
                "\"local_anchor_id\":\"local-anchor-1\"",
                "\"partition_key\":\"source_service:alert-service\"",
                "\"external_object_key\":\"" + objectKey + "\"",
                "\"chain_position\":1",
                "\"event_hash\":\"hash-1\"",
                "\"previous_event_hash\":null",
                "\"payload_hash\":\"",
                "\"created_at\":\"" + anchor.createdAt() + "\"",
                "\"published_at_local\":\"" + anchor.createdAt() + "\""
        );
    }

    @Test
    void shouldPreventUnsafePartitionKeyFromEscapingPrefix() {
        String objectKey = sink.objectKey("source_service:alert-service/../../other", 1L);

        assertThat(objectKey).startsWith("audit-anchors/");
        assertThat(objectKey).doesNotContain("../");
        assertThat(objectKey).endsWith("/00000000000000000001.json");
    }

    @Test
    void shouldTreatDuplicateWriteAsIdempotentWhenBindingMatches() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 1L, "hash-1");
        sink.publish(ExternalAuditAnchor.from(local, sink.sinkType()));
        client.resetCounters();

        ExternalAuditAnchor duplicate = sink.publish(ExternalAuditAnchor.from(local, sink.sinkType()));

        assertThat(duplicate.localAnchorId()).isEqualTo("local-anchor-1");
        assertThat(duplicate.chainPosition()).isEqualTo(1L);
        assertThat(duplicate.lastEventHash()).isEqualTo("hash-1");
        assertThat(client.anchorKeys()).hasSize(1);
        assertThat(client.getObjectCalls).isGreaterThanOrEqualTo(1);
        assertThat(client.listKeysCalls).isZero();
        assertThat(sink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1L);
    }

    @Test
    void shouldRebuildMissingHeadForIdempotentLegacyAnchor() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 1L, "hash-1");
        putLegacyAnchor("local-anchor-1", 1L, "hash-1");
        client.resetCounters();

        ExternalAuditAnchor duplicate = sink.publish(ExternalAuditAnchor.from(local, sink.sinkType()));

        assertThat(duplicate.localAnchorId()).isEqualTo("local-anchor-1");
        assertThat(duplicate.chainPosition()).isEqualTo(1L);
        assertThat(duplicate.lastEventHash()).isEqualTo("hash-1");
        assertThat(client.anchorKeys()).hasSize(1);
        assertThat(client.getObject("audit-bucket", sink.objectKey("source_service:alert-service", 1L))).isEmpty();
        assertThat(client.listKeysCalls).isZero();
        assertThat(client.listPageCalls).isEqualTo(1);
        assertThat(sink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1L);
    }

    @Test
    void shouldRejectOverwriteAttemptWhenSameKeyHasDifferentContent() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-2"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("CONFLICT");
    }

    @Test
    void shouldNotScanForSameLocalAnchorIdAtDifferentChainPosition() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));
        client.resetCounters();

        ExternalAuditAnchor stored = sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 2L, "hash-1"), sink.sinkType()));

        assertThat(stored.chainPosition()).isEqualTo(2L);
        assertThat(client.listKeysCalls).isZero();
        assertThat(client.listPageCalls).isZero();
    }

    @Test
    void shouldRejectSameChainPositionWithDifferentLocalAnchorId() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-2", 1L, "hash-1"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("CONFLICT");
    }

    @Test
    void shouldRejectLegacySameChainPositionWithDifferentLocalAnchorId() {
        putLegacyAnchor("local-anchor-1", 1L, "hash-1");

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-2", 1L, "hash-2"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorConflictException.class);
    }

    @Test
    void missingHeadShouldScanAllAnchorsBeforeAtomicCreate() {
        for (long chainPosition = 1L; chainPosition <= 20_000L; chainPosition++) {
            putPaddedAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition);
        }
        client.resetCounters();

        ExternalAuditAnchor stored = sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-20001", 20_001L, "hash-20001"), sink.sinkType()));

        assertThat(stored.chainPosition()).isEqualTo(20_001L);
        assertThat(client.listKeysCalls).isZero();
        assertThat(client.listPageCalls).isGreaterThan(1);
        assertThat(sink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(20_001L);
    }

    @Test
    void shouldReadAnchorByExactChainPositionWithoutScanning() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 7L, "hash-7"), sink.sinkType()));

        Optional<ExternalAuditAnchor> found = sink.findByChainPosition("source_service:alert-service", 7L);

        assertThat(found).get()
                .extracting(ExternalAuditAnchor::localAnchorId, ExternalAuditAnchor::chainPosition, ExternalAuditAnchor::lastEventHash)
                .containsExactly("local-anchor-1", 7L, "hash-7");
    }

    @Test
    void shouldReadLegacyAnchorByExactChainPosition() {
        putLegacyAnchor("local-anchor-7", 7L, "hash-7");

        Optional<ExternalAuditAnchor> found = sink.findByChainPosition("source_service:alert-service", 7L);

        assertThat(found).get()
                .extracting(ExternalAuditAnchor::localAnchorId, ExternalAuditAnchor::chainPosition, ExternalAuditAnchor::lastEventHash)
                .containsExactly("local-anchor-7", 7L, "hash-7");
    }

    @Test
    void shouldReturnLatestAndBoundedRangeFromObjectKeys() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-2", 2L, "hash-2"), sink.sinkType()));

        assertThat(sink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(2L);
        assertThat(sink.findByRange("source_service:alert-service", null, null, 1))
                .extracting(ExternalAuditAnchor::chainPosition)
                .containsExactly(1L);
    }

    @Test
    void shouldReturnCorrectHeadWithPaginatedListingBeyond16000() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectStoreExternalAuditAnchorSink scanningSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                client,
                objectMapper,
                new AlertServiceMetrics(meterRegistry),
                Duration.ofSeconds(2),
                Duration.ZERO,
                1,
                ExternalImmutabilityLevel.CONFIGURED
        );
        for (long chainPosition = 1L; chainPosition <= 10_000L; chainPosition++) {
            putPaddedAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition);
        }
        for (long chainPosition = 10_001L; chainPosition <= 20_000L; chainPosition++) {
            putLegacyAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition);
        }

        Optional<ExternalAuditAnchor> latest = scanningSink.latest("source_service:alert-service");

        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(20_000L);
        assertThat(scanningSink.findByChainPosition("source_service:alert-service", 1L)).isPresent();
        assertThat(scanningSink.findByChainPosition("source_service:alert-service", 20_000L)).isPresent();
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_head_scan_depth")
                .summary()
                .max()).isEqualTo(20_000.0d);
    }

    @Test
    void shouldUseManifestForLatestWithoutFullScan() {
        for (long chainPosition = 1L; chainPosition < 10_000L; chainPosition++) {
            putPaddedAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition);
        }
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-10000", 10_000L, "hash-10000"), sink.sinkType()));
        client.listPageCalls = 0;

        Optional<ExternalAuditAnchor> latest = sink.latest("source_service:alert-service");

        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(10_000L);
        assertThat(client.listPageCalls).isZero();
    }

    @Test
    void shouldFallbackToScanWhenManifestMissing() {
        putPaddedAnchor("local-anchor-1", 1L, "hash-1");
        putPaddedAnchor("local-anchor-2", 2L, "hash-2");

        Optional<ExternalAuditAnchor> latest = sink.latest("source_service:alert-service");

        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(2L);
        assertThat(client.listPageCalls).isGreaterThan(0);
    }

    @Test
    void shouldNotReturnBestEffortHeadWhenScannedAnchorPayloadIsTampered() {
        putPaddedAnchor("local-anchor-1", 1L, "hash-1");
        putPaddedAnchor("local-anchor-2", 2L, "hash-2");
        String secondKey = sink.objectKey("source_service:alert-service", 2L);
        client.putRaw("audit-bucket", secondKey, new String(client.getObject("audit-bucket", secondKey).orElseThrow(), StandardCharsets.UTF_8)
                .replace("\"event_hash\":\"hash-2\"", "\"event_hash\":\"hash-X\"")
                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> sink.latest("source_service:alert-service"))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("EXTERNAL_ANCHOR_ID_MISMATCH");
    }

    @Test
    void shouldFallbackToScanWhenManifestTampered() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectStoreExternalAuditAnchorSink manifestSink = sinkWithMetrics(meterRegistry);
        manifestSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), manifestSink.sinkType()));
        manifestSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-2", 2L, "hash-2"), manifestSink.sinkType()));
        client.putRaw("audit-bucket", headManifestKey(), new String(client.getObject("audit-bucket", headManifestKey()).orElseThrow(), StandardCharsets.UTF_8)
                .replace("\"latest_event_hash\":\"hash-2\"", "\"latest_event_hash\":\"hash-X\"")
                .getBytes(StandardCharsets.UTF_8));

        Optional<ExternalAuditAnchor> latest = manifestSink.latest("source_service:alert-service");

        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(2L);
        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::manifestStatus)
                .isEqualTo(ExternalAuditAnchor.MANIFEST_STATUS_FAILED);
        assertThat(meterRegistry.get("external_manifest_read_total").tag("status", "INVALID").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("external_manifest_fallback_scan_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void shouldDetectManifestHashMismatch() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectStoreExternalAuditAnchorSink manifestSink = sinkWithMetrics(meterRegistry);
        manifestSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), manifestSink.sinkType()));
        client.putRaw("audit-bucket", headManifestKey(), new String(client.getObject("audit-bucket", headManifestKey()).orElseThrow(), StandardCharsets.UTF_8)
                .replace("\"manifest_hash\":\"", "\"manifest_hash\":\"tampered-")
                .getBytes(StandardCharsets.UTF_8));

        Optional<ExternalAuditAnchor> latest = manifestSink.latest("source_service:alert-service");

        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1L);
        assertThat(meterRegistry.get("external_manifest_invalid_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void shouldExposeMissingManifestAsDegradedUntilIdempotentRepairSucceeds() {
        ExternalAuditAnchor candidate = ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                sink.sinkType()
        );
        sink.publish(candidate);
        client.removeRaw("audit-bucket", headManifestKey());

        ExternalAuditAnchor degraded = sink.latest("source_service:alert-service").orElseThrow();

        assertThat(degraded.publicationStatus()).isEqualTo(ExternalAuditAnchor.STATUS_PUBLISHED);
        assertThat(degraded.manifestStatus()).isEqualTo(ExternalAuditAnchor.MANIFEST_STATUS_FAILED);

        ExternalAuditAnchor repaired = sink.publish(candidate);

        assertThat(repaired.manifestStatus()).isNull();
        assertThat(sink.latest("source_service:alert-service")).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1L);
    }

    @Test
    void shouldDetectManifestAnchorMissing() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectStoreExternalAuditAnchorSink manifestSink = sinkWithMetrics(meterRegistry);
        manifestSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), manifestSink.sinkType()));
        client.removeRaw("audit-bucket", sink.objectKey("source_service:alert-service", 1L));

        Optional<ExternalAuditAnchor> latest = manifestSink.latest("source_service:alert-service");

        assertThat(latest).isEmpty();
        assertThat(meterRegistry.get("external_manifest_mismatch_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void shouldNotRegressManifestOnLowerPositionWrite() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-10", 10L, "hash-10"), sink.sinkType()));

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-5", 5L, "hash-5"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorConflictException.class);

        Optional<ExternalAuditAnchor> latest = sink.latest("source_service:alert-service");
        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(10L);
    }

    @Test
    void shouldHandleConcurrentWritesSafely() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        ExternalAuditAnchor anchor = ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType());
        try {
            List<java.util.concurrent.Callable<Void>> tasks = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(ignored -> (java.util.concurrent.Callable<Void>) () -> {
                        sink.publish(anchor);
                        return null;
                    })
                    .toList();
            for (java.util.concurrent.Future<Void> future : executor.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(sink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1L);
    }

    @Test
    void independentWritersShouldNotRegressHeadAfterStaleRead() throws Exception {
        ObjectStoreExternalAuditAnchorSink writerA = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket", "audit-anchors", client, objectMapper
        );
        ObjectStoreExternalAuditAnchorSink writerB = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket", "audit-anchors", client, objectMapper
        );
        writerA.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-100", 100L, "hash-100"), writerA.sinkType()));
        client.pauseNextHeadCompareAndSwap();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ExternalAuditAnchor> staleWriter = executor.submit(() -> writerA.publish(
                    ExternalAuditAnchor.from(localAnchor("local-anchor-101", 101L, "hash-101"), writerA.sinkType())
            ));
            client.awaitPausedHeadCompareAndSwap();

            writerB.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-102", 102L, "hash-102"), writerB.sinkType()));
            client.releasePausedHeadCompareAndSwap();
            assertThat(staleWriter.get().chainPosition()).isEqualTo(101L);
        } finally {
            client.releasePausedHeadCompareAndSwap();
            executor.shutdownNow();
        }

        assertThat(writerA.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(102L);
        assertThat(writerB.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(102L);
    }

    @Test
    void independentWritersShouldPublishIdenticalPositionIdempotently() throws Exception {
        ObjectStoreExternalAuditAnchorSink writerA = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket", "audit-anchors", client, objectMapper
        );
        ObjectStoreExternalAuditAnchorSink writerB = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket", "audit-anchors", client, objectMapper
        );
        ExternalAuditAnchor anchor = ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                writerA.sinkType()
        );
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<ExternalAuditAnchor>> results = executor.invokeAll(List.of(
                    () -> writerA.publish(anchor),
                    () -> writerB.publish(anchor)
            ));
            assertThat(results.get(0).get().localAnchorId()).isEqualTo("local-anchor-1");
            assertThat(results.get(1).get().localAnchorId()).isEqualTo("local-anchor-1");
        } finally {
            executor.shutdownNow();
        }

        assertThat(writerA.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1L);
    }

    @Test
    void independentWritersShouldRejectConcurrentConflictingIdentityAtSamePosition() throws Exception {
        ObjectStoreExternalAuditAnchorSink writerA = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket", "audit-anchors", client, objectMapper
        );
        ObjectStoreExternalAuditAnchorSink writerB = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket", "audit-anchors", client, objectMapper
        );
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<ExternalAuditAnchor>> results = List.of(
                    executor.submit(() -> {
                        start.await();
                        return writerA.publish(ExternalAuditAnchor.from(
                                localAnchor("local-anchor-A", 1L, "hash-A"), writerA.sinkType()
                        ));
                    }),
                    executor.submit(() -> {
                        start.await();
                        return writerB.publish(ExternalAuditAnchor.from(
                                localAnchor("local-anchor-B", 1L, "hash-B"), writerB.sinkType()
                        ));
                    })
            );
            start.countDown();
            int published = 0;
            int conflicts = 0;
            for (java.util.concurrent.Future<ExternalAuditAnchor> result : results) {
                try {
                    result.get();
                    published++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(ExternalAuditAnchorConflictException.class);
                    conflicts++;
                }
            }
            assertThat(published).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(writerA.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1L);
    }

    @Test
    void exhaustedCompareAndSwapShouldFailManifestButAllowRestartRecovery() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));
        client.alwaysConflictHeadCompareAndSwap = true;

        ExternalAuditAnchor partiallyPublished = sink.publish(
                ExternalAuditAnchor.from(localAnchor("local-anchor-2", 2L, "hash-2"), sink.sinkType())
        );

        assertThat(partiallyPublished.publicationStatus()).isEqualTo(ExternalAuditAnchor.STATUS_PUBLISHED);
        assertThat(partiallyPublished.manifestStatus()).isEqualTo(ExternalAuditAnchor.MANIFEST_STATUS_FAILED);
        assertThat(partiallyPublished.publicationReason()).isEqualTo(ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED);
        assertThat(client.compareAndSwapCalls).isEqualTo(2);
        assertThat(sink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1L);

        client.alwaysConflictHeadCompareAndSwap = false;
        ObjectStoreExternalAuditAnchorSink restarted = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket", "audit-anchors", client, objectMapper
        );
        restarted.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-2", 2L, "hash-2"), restarted.sinkType()));

        assertThat(restarted.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(2L);
    }

    @Test
    void manifestRepairCasConflictShouldRemainRecordedAsUnresolvedEvidence() {
        ExternalAuditHeadForensicService forensicService = mock(ExternalAuditHeadForensicService.class);
        when(forensicService.recordIncident(
                anyString(), anyString(), anyString(), anyString(), any(Instant.class)
        )).thenReturn("external-head-incident-3");
        ObjectStoreExternalAuditAnchorSink forensicSink = sinkWithForensicEvidence(forensicService);
        forensicSink.publish(ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                forensicSink.sinkType()
        ));
        byte[] manifest = client.getObject("audit-bucket", headManifestKey()).orElseThrow();
        client.putRaw(
                "audit-bucket",
                headManifestKey(),
                new String(manifest, StandardCharsets.UTF_8)
                        .replace("\"manifest_hash\":\"", "\"manifest_hash\":\"tampered-")
                        .getBytes(StandardCharsets.UTF_8)
        );
        client.alwaysConflictHeadCompareAndSwap = true;

        ExternalAuditAnchor result = forensicSink.publish(ExternalAuditAnchor.from(
                localAnchor("local-anchor-2", 2L, "hash-2"),
                forensicSink.sinkType()
        ));

        assertThat(result.manifestStatus()).isEqualTo(ExternalAuditAnchor.MANIFEST_STATUS_FAILED);
        verify(forensicService).recordRepairOutcome(
                eq("external-head-incident-3"),
                eq("CAS_CONFLICT"),
                org.mockito.ArgumentMatchers.any(Instant.class)
        );
    }

    @Test
    void validStaleHeadShouldBeRecordedOnlyWhenRepairingAnExistingAnchor() {
        ExternalAuditHeadForensicService forensicService = mock(ExternalAuditHeadForensicService.class);
        when(forensicService.recordIncident(
                anyString(), anyString(), anyString(), anyString(), any(Instant.class)
        )).thenReturn("external-head-incident-4");
        ObjectStoreExternalAuditAnchorSink forensicSink = sinkWithForensicEvidence(forensicService);
        forensicSink.publish(ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                forensicSink.sinkType()
        ));
        putPaddedAnchor("local-anchor-2", 2L, "hash-2");

        forensicSink.publish(ExternalAuditAnchor.from(
                localAnchor("local-anchor-2", 2L, "hash-2"),
                forensicSink.sinkType()
        ));

        verify(forensicService).recordIncident(
                eq("source_service:alert-service"),
                anyString(),
                anyString(),
                eq("HEAD_MANIFEST_STALE"),
                any(Instant.class)
        );
        verify(forensicService).recordRepairOutcome(
                eq("external-head-incident-4"),
                eq("VERIFIED_REPAIRED"),
                any(Instant.class)
        );
        assertThat(forensicSink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(2L);
    }

    @Test
    void timedOutCompareAndSwapShouldNotReportHealthyManifestUpdate() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));
        client.headCompareAndSwapDelay = Duration.ofMillis(200);
        ObjectStoreExternalAuditAnchorSink shortTimeoutSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                client,
                objectMapper,
                null,
                Duration.ofMillis(10),
                Duration.ZERO,
                1,
                ExternalImmutabilityLevel.NONE
        );

        ExternalAuditAnchor partiallyPublished = shortTimeoutSink.publish(
                ExternalAuditAnchor.from(localAnchor("local-anchor-2", 2L, "hash-2"), shortTimeoutSink.sinkType())
        );

        assertThat(partiallyPublished.publicationStatus()).isEqualTo(ExternalAuditAnchor.STATUS_PUBLISHED);
        assertThat(partiallyPublished.manifestStatus()).isEqualTo(ExternalAuditAnchor.MANIFEST_STATUS_FAILED);
        assertThat(partiallyPublished.publicationReason()).isEqualTo(ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED);
    }

    @Test
    void malformedManifestShouldBeRepairedOnlyByVersionMatchedWrite() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));
        client.putRaw("audit-bucket", headManifestKey(), "not-json".getBytes(StandardCharsets.UTF_8));
        client.resetCounters();

        ExternalAuditAnchor stored = sink.publish(
                ExternalAuditAnchor.from(localAnchor("local-anchor-2", 2L, "hash-2"), sink.sinkType())
        );

        assertThat(stored.manifestStatus()).isNull();
        assertThat(client.compareAndSwapCalls).isEqualTo(1);
        assertThat(client.putObjectCalls).isZero();
        assertThat(sink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(2L);
    }

    @Test
    void tamperedManifestShouldBeRepairedOnlyByVersionMatchedWrite() {
        ExternalAuditHeadForensicService forensicService = mock(ExternalAuditHeadForensicService.class);
        when(forensicService.recordIncident(
                anyString(), anyString(), anyString(), anyString(), any(Instant.class)
        )).thenReturn("external-head-incident-1");
        ObjectStoreExternalAuditAnchorSink forensicSink = sinkWithForensicEvidence(forensicService);
        forensicSink.publish(ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                forensicSink.sinkType()
        ));
        byte[] manifest = client.getObject("audit-bucket", headManifestKey()).orElseThrow();
        client.putRaw(
                "audit-bucket",
                headManifestKey(),
                new String(manifest, StandardCharsets.UTF_8)
                        .replace("\"manifest_hash\":\"", "\"manifest_hash\":\"tampered-")
                        .getBytes(StandardCharsets.UTF_8)
        );
        client.resetCounters();

        ExternalAuditAnchor stored = forensicSink.publish(
                ExternalAuditAnchor.from(localAnchor("local-anchor-2", 2L, "hash-2"), forensicSink.sinkType())
        );

        assertThat(stored.manifestStatus()).isNull();
        assertThat(client.compareAndSwapCalls).isEqualTo(1);
        assertThat(client.putObjectCalls).isZero();
        assertThat(forensicSink.latest("source_service:alert-service"))
                .get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(2L);
        org.mockito.InOrder evidenceOrder = org.mockito.Mockito.inOrder(forensicService);
        evidenceOrder.verify(forensicService).recordIncident(
                eq("source_service:alert-service"),
                anyString(),
                anyString(),
                eq("HEAD_MANIFEST_HASH_MISMATCH"),
                any(Instant.class)
        );
        evidenceOrder.verify(forensicService).recordRepairOutcome(
                eq("external-head-incident-1"),
                eq("VERIFIED_REPAIRED"),
                org.mockito.ArgumentMatchers.any(Instant.class)
        );
    }

    @Test
    void missingHeadDetectionShouldRemainDurableAcrossRestartBeforeRepair() {
        ExternalAuditHeadForensicService forensicService = mock(ExternalAuditHeadForensicService.class);
        when(forensicService.recordIncident(
                anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                anyString(), any(Instant.class)
        )).thenReturn("external-head-incident-2");
        ObjectStoreExternalAuditAnchorSink initial = sinkWithForensicEvidence(forensicService);
        ExternalAuditAnchor candidate = ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                initial.sinkType()
        );
        initial.publish(candidate);
        client.removeRaw("audit-bucket", headManifestKey());

        assertThat(initial.latest("source_service:alert-service")).isPresent();
        verify(forensicService).recordIncident(
                eq("source_service:alert-service"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                eq("HEAD_MANIFEST_MISSING"),
                any(Instant.class)
        );

        ObjectStoreExternalAuditAnchorSink restarted = sinkWithForensicEvidence(forensicService);
        restarted.publish(candidate);

        verify(forensicService).recordRepairOutcome(
                eq("external-head-incident-2"),
                eq("VERIFIED_REPAIRED"),
                org.mockito.ArgumentMatchers.any(Instant.class)
        );
        assertThat(restarted.latest("source_service:alert-service")).isPresent();
    }

    @Test
    void shouldNotReturnManifestIfInvalid() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));
        putPaddedAnchor("local-anchor-2", 2L, "hash-2");
        client.putRaw("audit-bucket", headManifestKey(), new String(client.getObject("audit-bucket", headManifestKey()).orElseThrow(), StandardCharsets.UTF_8)
                .replace("\"latest_chain_position\":1", "\"latest_chain_position\":2")
                .getBytes(StandardCharsets.UTF_8));

        Optional<ExternalAuditAnchor> latest = sink.latest("source_service:alert-service");

        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(2L);
    }

    @Test
    void shouldRejectNonPaginatedFullPageInsteadOfReturningBestEffortHead() {
        InMemoryObjectStoreAuditAnchorClient nonPaginatedClient = new InMemoryObjectStoreAuditAnchorClient();
        nonPaginatedClient.paginationSupported = false;
        ObjectStoreExternalAuditAnchorSink nonPaginatedSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                nonPaginatedClient,
                objectMapper
        );
        for (long chainPosition = 1L; chainPosition <= 500L; chainPosition++) {
            putAnchorObject(
                    nonPaginatedClient,
                    nonPaginatedSink.objectKey("source_service:alert-service", chainPosition),
                    ExternalAuditAnchor.from(localAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition), nonPaginatedSink.sinkType())
            );
        }

        assertThatThrownBy(() -> nonPaginatedSink.latest("source_service:alert-service"))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("HEAD_SCAN_PAGINATION_UNSUPPORTED");
    }

    @Test
    void shouldFailWhenWriteCannotBeReadBack() {
        InMemoryObjectStoreAuditAnchorClient unreadableClient = new InMemoryObjectStoreAuditAnchorClient();
        unreadableClient.hideWritesAfterPut = true;
        ObjectStoreExternalAuditAnchorSink unreadableSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                unreadableClient,
                objectMapper
        );

        ExternalAuditAnchor stored = unreadableSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), unreadableSink.sinkType()));

        assertThat(stored.publicationStatus()).isEqualTo(ExternalAuditAnchor.STATUS_UNVERIFIED);
        assertThat(stored.publicationReason()).isEqualTo("WRITE_NOT_VERIFIED");
    }

    @Test
    void shouldFailWhenReadBackPayloadHashIsTampered() {
        InMemoryObjectStoreAuditAnchorClient tamperingClient = new InMemoryObjectStoreAuditAnchorClient();
        tamperingClient.tamperAfterPut = true;
        ObjectStoreExternalAuditAnchorSink tamperingSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                tamperingClient,
                objectMapper
        );

        ExternalAuditAnchor stored = tamperingSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), tamperingSink.sinkType()));

        assertThat(stored.publicationStatus()).isEqualTo(ExternalAuditAnchor.STATUS_INVALID);
        assertThat(stored.publicationReason()).isEqualTo("EXTERNAL_ANCHOR_ID_MISMATCH");
    }

    @Test
    void shouldKeepAnchorPublishedWhenOnlyManifestUpdateFails() {
        InMemoryObjectStoreAuditAnchorClient manifestFailingClient = new InMemoryObjectStoreAuditAnchorClient();
        manifestFailingClient.failHeadManifestPut = true;
        ObjectStoreExternalAuditAnchorSink manifestFailingSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                manifestFailingClient,
                objectMapper
        );

        ExternalAuditAnchor stored = manifestFailingSink.publish(ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                manifestFailingSink.sinkType()
        ));

        assertThat(stored.publicationStatus()).isEqualTo(ExternalAuditAnchor.STATUS_PUBLISHED);
        assertThat(stored.publicationReason()).isEqualTo(ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED);
        assertThat(stored.manifestStatus()).isEqualTo(ExternalAuditAnchor.MANIFEST_STATUS_FAILED);
        assertThat(manifestFailingClient.getObject("audit-bucket", manifestFailingSink.objectKey("source_service:alert-service", 1L))).isPresent();
    }

    @Test
    void shouldUseWitnessTimestampValueRatherThanApplicationReadBackTimeForReference() {
        InMemoryObjectStoreAuditAnchorClient timestampedClient = new InMemoryObjectStoreAuditAnchorClient();
        timestampedClient.timestampValue = Instant.parse("2026-04-27T09:59:59Z");
        timestampedClient.timestampType = ExternalWitnessTimestampType.STORAGE_OBSERVED;
        timestampedClient.timestampSource = "OBJECT_METADATA";
        timestampedClient.timestampVerified = true;
        ObjectStoreExternalAuditAnchorSink timestampedSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                timestampedClient,
                objectMapper,
                null,
                Duration.ofSeconds(2),
                Duration.ZERO,
                1,
                ExternalImmutabilityLevel.ENFORCED,
                ExternalWitnessCapabilities.objectStore(
                        "test-witness",
                        ExternalWitnessIndependenceLevel.SEPARATE_ACCOUNT.name(),
                        ExternalImmutabilityLevel.ENFORCED,
                        true,
                        true,
                        true,
                        true,
                        ExternalWitnessTimestampType.STORAGE_OBSERVED,
                        ExternalTimestampTrustLevel.MEDIUM.name(),
                        true,
                        true,
                        false,
                        ExternalDurabilityGuarantee.WITNESS_RETENTION
                )
        );

        ExternalAuditAnchor stored = timestampedSink.publish(ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                timestampedSink.sinkType()
        ));
        ExternalAnchorReference reference = timestampedSink.externalReference(stored).orElseThrow();

        assertThat(reference.timestampValue()).isEqualTo(Instant.parse("2026-04-27T09:59:59Z"));
        assertThat(reference.verifiedAt()).isNotEqualTo(reference.timestampValue());
        assertThat(reference.timestampType()).isEqualTo(ExternalWitnessTimestampType.STORAGE_OBSERVED);
        assertThat(reference.timestampTrustLevel()).isEqualTo(ExternalTimestampTrustLevel.MEDIUM.name());
        assertThat(reference.timestampSource()).isEqualTo("OBJECT_METADATA");
        assertThat(reference.timestampVerified()).isTrue();
    }

    @Test
    void shouldTreatMissingWitnessTimestampAsApplicationObservedWeakSignal() {
        InMemoryObjectStoreAuditAnchorClient timestamplessClient = new InMemoryObjectStoreAuditAnchorClient();
        ObjectStoreExternalAuditAnchorSink timestamplessSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                timestamplessClient,
                objectMapper
        );

        ExternalAuditAnchor stored = timestamplessSink.publish(ExternalAuditAnchor.from(
                localAnchor("local-anchor-1", 1L, "hash-1"),
                timestamplessSink.sinkType()
        ));
        ExternalAnchorReference reference = timestamplessSink.externalReference(stored).orElseThrow();

        assertThat(reference.timestampValue()).isNull();
        assertThat(reference.timestampType()).isEqualTo(ExternalWitnessTimestampType.APP_OBSERVED);
        assertThat(reference.timestampTrustLevel()).isEqualTo(ExternalTimestampTrustLevel.WEAK.name());
        assertThat(reference.timestampSource()).isEqualTo("APP_CLOCK");
        assertThat(reference.timestampVerified()).isFalse();
    }

    @Test
    void shouldRejectNewLowerPositionWhenManifestHeadIsHigher() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-10", 10L, "hash-10"), sink.sinkType()));

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-5", 5L, "hash-5"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorConflictException.class);
    }

    @Test
    void shouldDetectObjectStoredUnderWrongKey() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));
        String correctKey = sink.objectKey("source_service:alert-service", 1L);
        String wrongKey = sink.objectKey("source_service:alert-service", 2L);
        client.putRaw("audit-bucket", wrongKey, client.getObject("audit-bucket", correctKey).orElseThrow());

        assertThatThrownBy(() -> sink.findByChainPosition("source_service:alert-service", 2L))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("EXTERNAL_OBJECT_KEY_MISMATCH");
    }

    @Test
    void shouldRetryBoundedTransientExternalStoreFailure() {
        InMemoryObjectStoreAuditAnchorClient retryingClient = new InMemoryObjectStoreAuditAnchorClient();
        retryingClient.failNextListCalls = 1;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectStoreExternalAuditAnchorSink retryingSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                retryingClient,
                objectMapper,
                new AlertServiceMetrics(meterRegistry),
                Duration.ofSeconds(2),
                Duration.ZERO,
                2,
                ExternalImmutabilityLevel.CONFIGURED
        );

        putAnchorObject(
                retryingClient,
                retryingSink.objectKey("source_service:alert-service", 1L),
                ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), retryingSink.sinkType())
        );
        retryingSink.latest("source_service:alert-service");

        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_retry_total")
                .tag("operation", "list")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldFailAfterBoundedTimeout() {
        InMemoryObjectStoreAuditAnchorClient slowClient = new InMemoryObjectStoreAuditAnchorClient();
        slowClient.getDelay = Duration.ofMillis(200);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectStoreExternalAuditAnchorSink slowSink = new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                slowClient,
                objectMapper,
                new AlertServiceMetrics(meterRegistry),
                Duration.ofMillis(10),
                Duration.ZERO,
                1,
                ExternalImmutabilityLevel.CONFIGURED
        );

        assertThatThrownBy(() -> slowSink.findByChainPosition("source_service:alert-service", 1L))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("TIMEOUT");
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_timeout_total")
                .tag("operation", "get")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private AuditAnchorDocument localAnchor(String anchorId, long chainPosition, String hash) {
        return new AuditAnchorDocument(
                anchorId,
                Instant.parse("2026-04-27T10:00:00Z"),
                "source_service:alert-service",
                hash,
                chainPosition,
                "SHA-256"
        );
    }

    private void putPaddedAnchor(String anchorId, long chainPosition, String hash) {
        String key = sink.objectKey("source_service:alert-service", chainPosition);
        putAnchorObject(key, ExternalAuditAnchor.from(localAnchor(anchorId, chainPosition, hash), sink.sinkType()));
    }

    private void putLegacyAnchor(String anchorId, long chainPosition, String hash) {
        String key = "audit-anchors/" + ENCODED_PARTITION + "/" + chainPosition + ".json";
        putAnchorObject(key, ExternalAuditAnchor.from(localAnchor(anchorId, chainPosition, hash), sink.sinkType()));
    }

    private ObjectStoreExternalAuditAnchorSink sinkWithMetrics(SimpleMeterRegistry meterRegistry) {
        return new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                client,
                objectMapper,
                new AlertServiceMetrics(meterRegistry),
                Duration.ofSeconds(2),
                Duration.ZERO,
                1,
                ExternalImmutabilityLevel.CONFIGURED
        );
    }

    private ObjectStoreExternalAuditAnchorSink sinkWithForensicEvidence(ExternalAuditHeadForensicService forensicService) {
        return new ObjectStoreExternalAuditAnchorSink(
                "audit-bucket",
                "audit-anchors",
                client,
                objectMapper,
                null,
                Duration.ofSeconds(2),
                Duration.ZERO,
                1,
                ExternalImmutabilityLevel.CONFIGURED,
                ExternalWitnessCapabilities.objectStore(ExternalImmutabilityLevel.CONFIGURED),
                forensicService
        );
    }

    private String headManifestKey() {
        return "audit-anchors/" + ENCODED_PARTITION + "/head.json";
    }

    private void putAnchorObject(String key, ExternalAuditAnchor anchor) {
        putAnchorObject(client, key, anchor);
    }

    private void putAnchorObject(InMemoryObjectStoreAuditAnchorClient targetClient, String key, ExternalAuditAnchor anchor) {
        ObjectStoreExternalAuditAnchorPayload unsigned = ObjectStoreExternalAuditAnchorPayload.from(anchor, key, null);
        ObjectStoreExternalAuditAnchorPayload signed = ObjectStoreExternalAuditAnchorPayload.from(
                anchor,
                key,
                sha256Hex(serialize(unsigned))
        );
        targetClient.putRaw("audit-bucket", key, serialize(signed));
    }

    private byte[] serialize(ObjectStoreExternalAuditAnchorPayload payload) {
        try {
            return canonicalObjectMapper.writeValueAsBytes(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static class InMemoryObjectStoreAuditAnchorClient implements ObjectStoreAuditAnchorClient {

        private final Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong versionSequence = new java.util.concurrent.atomic.AtomicLong();
        boolean hideWritesAfterPut;
        boolean tamperAfterPut;
        int failNextListCalls;
        Duration getDelay = Duration.ZERO;
        boolean paginationSupported = true;
        boolean failHeadManifestPut;
        Instant timestampValue;
        ExternalWitnessTimestampType timestampType = ExternalWitnessTimestampType.APP_OBSERVED;
        String timestampSource = "APP_CLOCK";
        boolean timestampVerified;
        int getObjectCalls;
        int putObjectCalls;
        int putObjectIfAbsentCalls;
        int compareAndSwapCalls;
        int listKeysCalls;
        int listPageCalls;
        volatile boolean alwaysConflictHeadCompareAndSwap;
        volatile Duration headCompareAndSwapDelay = Duration.ZERO;
        private final java.util.concurrent.atomic.AtomicBoolean pauseNextHeadCompareAndSwap =
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile java.util.concurrent.CountDownLatch pausedHeadCompareAndSwap;
        private volatile java.util.concurrent.CountDownLatch releaseHeadCompareAndSwap;

        @Override
        public ObjectStoreAuditAnchorHeadWriteCapability headWriteCapability() {
            return ObjectStoreAuditAnchorHeadWriteCapability.LINEARIZABLE_CREATE_AND_COMPARE_AND_SWAP;
        }

        @Override
        public Optional<byte[]> getObject(String bucket, String key) {
            getObjectCalls++;
            delay(getDelay);
            return Optional.ofNullable(objects.get(bucket + "/" + key));
        }

        @Override
        public Optional<ObjectStoreAuditAnchorObject> getObjectWithMetadata(String bucket, String key) {
            return getObject(bucket, key)
                    .map(content -> new ObjectStoreAuditAnchorObject(
                            content,
                            timestampValue,
                            timestampType,
                            timestampSource,
                            timestampVerified
                    ));
        }

        @Override
        public synchronized void putObjectIfAbsent(String bucket, String key, byte[] content) {
            putObjectIfAbsentCalls++;
            if (failHeadManifestPut && key.endsWith("/head.json")) {
                throw new ExternalAuditAnchorSinkException("HEAD_MANIFEST_UPDATE_FAILED", "External anchor head manifest is unavailable.");
            }
            byte[] stored = Arrays.copyOf(content, content.length);
            String storageKey = bucket + "/" + key;
            byte[] existing = objects.putIfAbsent(storageKey, stored);
            if (existing != null) {
                throw new ExternalAuditAnchorSinkException("CONFLICT", "Object already exists.");
            }
            versions.put(storageKey, versionSequence.incrementAndGet());
            if (hideWritesAfterPut) {
                objects.remove(storageKey);
                versions.remove(storageKey);
            }
            if (tamperAfterPut) {
                objects.put(storageKey, new String(stored, StandardCharsets.UTF_8)
                        .replace("\"event_hash\":\"hash-1\"", "\"event_hash\":\"hash-X\"")
                        .getBytes(StandardCharsets.UTF_8));
                versions.put(storageKey, versionSequence.incrementAndGet());
            }
        }

        @Override
        public synchronized Optional<ObjectStoreAuditAnchorVersionedObject> getObjectWithVersion(String bucket, String key) {
            String storageKey = bucket + "/" + key;
            byte[] content = objects.get(storageKey);
            Long version = versions.get(storageKey);
            if (content == null || version == null) {
                return Optional.empty();
            }
            return Optional.of(new ObjectStoreAuditAnchorVersionedObject(content, Long.toString(version)));
        }

        @Override
        public void putObjectIfVersionMatches(String bucket, String key, String expectedVersion, byte[] content) {
            compareAndSwapCalls++;
            pauseHeadCompareAndSwapIfRequested();
            delayHeadCompareAndSwap();
            synchronized (this) {
                if (alwaysConflictHeadCompareAndSwap) {
                    throw new ExternalAuditAnchorSinkException("CONFLICT", "Forced compare-and-swap conflict.");
                }
                String storageKey = bucket + "/" + key;
                Long currentVersion = versions.get(storageKey);
                if (currentVersion == null || !Long.toString(currentVersion).equals(expectedVersion)) {
                    throw new ExternalAuditAnchorSinkException("CONFLICT", "Object version changed.");
                }
                objects.put(storageKey, Arrays.copyOf(content, content.length));
                versions.put(storageKey, versionSequence.incrementAndGet());
            }
        }

        @Override
        public synchronized void putObject(String bucket, String key, byte[] content) {
            putObjectCalls++;
            if (failHeadManifestPut && key.endsWith("/head.json")) {
                throw new ExternalAuditAnchorSinkException("HEAD_MANIFEST_UPDATE_FAILED", "External anchor head manifest is unavailable.");
            }
            String storageKey = bucket + "/" + key;
            objects.put(storageKey, Arrays.copyOf(content, content.length));
            versions.put(storageKey, versionSequence.incrementAndGet());
        }

        @Override
        public List<String> listKeys(String bucket, String keyPrefix, int limit) {
            listKeysCalls++;
            if (failNextListCalls > 0) {
                failNextListCalls--;
                throw new IllegalStateException("transient list failure");
            }
            String bucketPrefix = bucket + "/" + keyPrefix;
            return objects.keySet().stream()
                    .filter(key -> key.startsWith(bucketPrefix))
                    .map(key -> key.substring(bucket.length() + 1))
                    .sorted(Comparator.naturalOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public ObjectStoreAuditAnchorKeyPage listKeysPage(String bucket, String keyPrefix, int limit, String continuationToken) {
            listPageCalls++;
            if (!paginationSupported) {
                return ObjectStoreAuditAnchorClient.super.listKeysPage(bucket, keyPrefix, limit, continuationToken);
            }
            if (failNextListCalls > 0) {
                failNextListCalls--;
                throw new IllegalStateException("transient list failure");
            }
            String bucketPrefix = bucket + "/" + keyPrefix;
            List<String> keys = objects.keySet().stream()
                    .filter(key -> key.startsWith(bucketPrefix))
                    .map(key -> key.substring(bucket.length() + 1))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            int offset = continuationToken == null ? 0 : Integer.parseInt(continuationToken);
            int nextOffset = Math.min(offset + limit, keys.size());
            String nextToken = nextOffset < keys.size() ? String.valueOf(nextOffset) : null;
            return new ObjectStoreAuditAnchorKeyPage(keys.subList(offset, nextOffset), nextToken);
        }

        List<String> keys() {
            return List.copyOf(objects.keySet());
        }

        List<String> anchorKeys() {
            return objects.keySet().stream()
                    .filter(key -> !key.endsWith("/head.json"))
                    .toList();
        }

        synchronized void putRaw(String bucket, String key, byte[] content) {
            String storageKey = bucket + "/" + key;
            objects.put(storageKey, Arrays.copyOf(content, content.length));
            versions.put(storageKey, versionSequence.incrementAndGet());
        }

        synchronized void removeRaw(String bucket, String key) {
            String storageKey = bucket + "/" + key;
            objects.remove(storageKey);
            versions.remove(storageKey);
        }

        void resetCounters() {
            getObjectCalls = 0;
            putObjectCalls = 0;
            putObjectIfAbsentCalls = 0;
            compareAndSwapCalls = 0;
            listKeysCalls = 0;
            listPageCalls = 0;
        }

        void pauseNextHeadCompareAndSwap() {
            pausedHeadCompareAndSwap = new java.util.concurrent.CountDownLatch(1);
            releaseHeadCompareAndSwap = new java.util.concurrent.CountDownLatch(1);
            pauseNextHeadCompareAndSwap.set(true);
        }

        void awaitPausedHeadCompareAndSwap() throws InterruptedException {
            if (!pausedHeadCompareAndSwap.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("Compare-and-swap did not pause.");
            }
        }

        void releasePausedHeadCompareAndSwap() {
            releaseHeadCompareAndSwap.countDown();
        }

        private void pauseHeadCompareAndSwapIfRequested() {
            if (!pauseNextHeadCompareAndSwap.compareAndSet(true, false)) {
                return;
            }
            pausedHeadCompareAndSwap.countDown();
            try {
                if (!releaseHeadCompareAndSwap.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new ExternalAuditAnchorSinkException("TIMEOUT", "Compare-and-swap release timed out.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExternalAuditAnchorSinkException("TIMEOUT", "Compare-and-swap was interrupted.");
            }
        }

        private void delayHeadCompareAndSwap() {
            if (headCompareAndSwapDelay.isZero()) {
                return;
            }
            try {
                Thread.sleep(headCompareAndSwapDelay.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExternalAuditAnchorSinkException("TIMEOUT", "Compare-and-swap was interrupted.");
            }
        }

        private void delay(Duration duration) {
            if (duration.isZero()) {
                return;
            }
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
