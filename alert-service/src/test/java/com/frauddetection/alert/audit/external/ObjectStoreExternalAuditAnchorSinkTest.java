package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.frauddetection.alert.audit.AuditAnchorDocument;
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

class ObjectStoreExternalAuditAnchorSinkTest {

    private static final String ENCODED_PARTITION = "c291cmNlX3NlcnZpY2U6YWxlcnQtc2VydmljZQ";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ObjectMapper canonicalObjectMapper = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build()
            .findAndRegisterModules();
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
        assertThat(client.getObjectCalls).isEqualTo(1);
        assertThat(client.listKeysCalls).isZero();
        assertThat(client.listPageCalls).isZero();
    }

    @Test
    void shouldTreatLegacyAnchorAsIdempotentWithoutScanning() {
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
        assertThat(client.listPageCalls).isZero();
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
    void publishNewAnchorShouldNotListAllKeys() {
        for (long chainPosition = 1L; chainPosition <= 20_000L; chainPosition++) {
            putPaddedAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition);
        }
        client.resetCounters();

        ExternalAuditAnchor stored = sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-20001", 20_001L, "hash-20001"), sink.sinkType()));

        assertThat(stored.chainPosition()).isEqualTo(20_001L);
        assertThat(client.listKeysCalls).isZero();
        assertThat(client.listPageCalls).isZero();
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
        } catch (JsonProcessingException exception) {
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
        int listKeysCalls;
        int listPageCalls;

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
        public void putObjectIfAbsent(String bucket, String key, byte[] content) {
            putObjectIfAbsentCalls++;
            byte[] stored = Arrays.copyOf(content, content.length);
            byte[] existing = objects.putIfAbsent(bucket + "/" + key, stored);
            if (existing != null) {
                throw new ExternalAuditAnchorSinkException("CONFLICT", "Object already exists.");
            }
            if (hideWritesAfterPut) {
                objects.remove(bucket + "/" + key);
            }
            if (tamperAfterPut) {
                objects.put(bucket + "/" + key, new String(stored, StandardCharsets.UTF_8)
                        .replace("\"event_hash\":\"hash-1\"", "\"event_hash\":\"hash-X\"")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void putObject(String bucket, String key, byte[] content) {
            putObjectCalls++;
            if (failHeadManifestPut && key.endsWith("/head.json")) {
                throw new ExternalAuditAnchorSinkException("HEAD_MANIFEST_UPDATE_FAILED", "External anchor head manifest is unavailable.");
            }
            objects.put(bucket + "/" + key, Arrays.copyOf(content, content.length));
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

        void putRaw(String bucket, String key, byte[] content) {
            objects.put(bucket + "/" + key, Arrays.copyOf(content, content.length));
        }

        void removeRaw(String bucket, String key) {
            objects.remove(bucket + "/" + key);
        }

        void resetCounters() {
            getObjectCalls = 0;
            putObjectCalls = 0;
            putObjectIfAbsentCalls = 0;
            listKeysCalls = 0;
            listPageCalls = 0;
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
