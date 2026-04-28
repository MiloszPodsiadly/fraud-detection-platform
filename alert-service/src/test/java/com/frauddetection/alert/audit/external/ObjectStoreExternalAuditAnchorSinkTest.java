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
import java.util.LinkedHashMap;
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
                "\"local_anchor_id\":\"local-anchor-1\"",
                "\"partition_key\":\"source_service:alert-service\"",
                "\"external_object_key\":\"" + objectKey + "\"",
                "\"chain_position\":1",
                "\"event_hash\":\"hash-1\"",
                "\"payload_hash\":\"",
                "\"created_at\":\"" + anchor.createdAt() + "\""
        );
        assertThat(storedJson).doesNotContain("previous_event_hash");
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

        ExternalAuditAnchor duplicate = sink.publish(ExternalAuditAnchor.from(local, sink.sinkType()));

        assertThat(duplicate.localAnchorId()).isEqualTo("local-anchor-1");
        assertThat(duplicate.chainPosition()).isEqualTo(1L);
        assertThat(duplicate.lastEventHash()).isEqualTo("hash-1");
        assertThat(client.keys()).hasSize(1);
    }

    @Test
    void shouldTreatLegacyAnchorAsIdempotentAfterKeyFormatMigration() {
        AuditAnchorDocument local = localAnchor("local-anchor-1", 1L, "hash-1");
        putLegacyAnchor("local-anchor-1", 1L, "hash-1");

        ExternalAuditAnchor duplicate = sink.publish(ExternalAuditAnchor.from(local, sink.sinkType()));

        assertThat(duplicate.localAnchorId()).isEqualTo("local-anchor-1");
        assertThat(duplicate.chainPosition()).isEqualTo(1L);
        assertThat(duplicate.lastEventHash()).isEqualTo("hash-1");
        assertThat(client.keys()).hasSize(1);
        assertThat(client.getObject("audit-bucket", sink.objectKey("source_service:alert-service", 1L))).isEmpty();
    }

    @Test
    void shouldRejectOverwriteAttemptWhenSameKeyHasDifferentContent() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-2"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("MISMATCH");
    }

    @Test
    void shouldRejectSameLocalAnchorIdWithDifferentChainPosition() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 2L, "hash-1"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("MISMATCH");
    }

    @Test
    void shouldRejectSameChainPositionWithDifferentLocalAnchorId() {
        sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), sink.sinkType()));

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-2", 1L, "hash-1"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("MISMATCH");
    }

    @Test
    void shouldRejectLegacySameChainPositionWithDifferentLocalAnchorId() {
        putLegacyAnchor("local-anchor-1", 1L, "hash-1");

        assertThatThrownBy(() -> sink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-2", 1L, "hash-2"), sink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("MISMATCH");
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
    void shouldReturnCorrectHeadBeyondListingLimit() {
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
        for (long chainPosition = 1L; chainPosition <= 500L; chainPosition++) {
            putPaddedAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition);
        }
        for (long chainPosition = 501L; chainPosition <= 1000L; chainPosition++) {
            putLegacyAnchor("local-anchor-" + chainPosition, chainPosition, "hash-" + chainPosition);
        }

        Optional<ExternalAuditAnchor> latest = scanningSink.latest("source_service:alert-service");

        assertThat(latest).get()
                .extracting(ExternalAuditAnchor::chainPosition)
                .isEqualTo(1000L);
        assertThat(scanningSink.findByChainPosition("source_service:alert-service", 1L)).isPresent();
        assertThat(scanningSink.findByChainPosition("source_service:alert-service", 1000L)).isPresent();
        assertThat(meterRegistry.get("fraud_platform_audit_external_anchor_head_scan_depth")
                .summary()
                .max()).isEqualTo(1000.0d);
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

        assertThatThrownBy(() -> unreadableSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), unreadableSink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("WRITE_NOT_VERIFIED");
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

        assertThatThrownBy(() -> tamperingSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), tamperingSink.sinkType())))
                .isInstanceOf(ExternalAuditAnchorSinkException.class)
                .extracting("reason")
                .isEqualTo("EXTERNAL_PAYLOAD_HASH_MISMATCH");
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

        retryingSink.publish(ExternalAuditAnchor.from(localAnchor("local-anchor-1", 1L, "hash-1"), retryingSink.sinkType()));

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

    private void putAnchorObject(String key, ExternalAuditAnchor anchor) {
        ObjectStoreExternalAuditAnchorPayload unsigned = ObjectStoreExternalAuditAnchorPayload.from(anchor, key, null);
        ObjectStoreExternalAuditAnchorPayload signed = ObjectStoreExternalAuditAnchorPayload.from(
                anchor,
                key,
                sha256Hex(serialize(unsigned))
        );
        client.putRaw("audit-bucket", key, serialize(signed));
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

        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        boolean hideWritesAfterPut;
        boolean tamperAfterPut;
        int failNextListCalls;
        Duration getDelay = Duration.ZERO;

        @Override
        public Optional<byte[]> getObject(String bucket, String key) {
            delay(getDelay);
            return Optional.ofNullable(objects.get(bucket + "/" + key));
        }

        @Override
        public void putObjectIfAbsent(String bucket, String key, byte[] content) {
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
        public List<String> listKeys(String bucket, String keyPrefix, int limit) {
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

        List<String> keys() {
            return List.copyOf(objects.keySet());
        }

        void putRaw(String bucket, String key, byte[] content) {
            objects.put(bucket + "/" + key, Arrays.copyOf(content, content.length));
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
