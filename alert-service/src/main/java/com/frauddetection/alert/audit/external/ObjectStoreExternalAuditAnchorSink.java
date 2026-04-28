package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

class ObjectStoreExternalAuditAnchorSink implements ExternalAuditAnchorSink {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreExternalAuditAnchorSink.class);
    private static final int LIST_LIMIT = 500;

    private final String bucket;
    private final String prefix;
    private final ObjectStoreAuditAnchorClient client;
    private final ObjectMapper objectMapper;

    ObjectStoreExternalAuditAnchorSink(
            String bucket,
            String prefix,
            ObjectStoreAuditAnchorClient client,
            ObjectMapper objectMapper
    ) {
        this.bucket = requireText(bucket, "bucket");
        this.prefix = trimSlashes(requireText(prefix, "prefix"));
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper = canonicalMapper(objectMapper);
    }

    @Override
    public String sinkType() {
        return "object-store";
    }

    @Override
    public ExternalAuditAnchor publish(ExternalAuditAnchor anchor) {
        String key = objectKey(anchor.partitionKey(), anchor.chainPosition());
        ObjectStoreExternalAuditAnchorPayload candidate = payload(anchor, key);
        byte[] payload = serialize(candidate);
        try {
            detectDuplicateAnchorConflict(anchor, key);
            Optional<byte[]> existing = client.getObject(bucket, key);
            if (existing.isPresent()) {
                return idempotentExisting(key, existing.get(), payload, candidate);
            }
            try {
                client.putObjectIfAbsent(bucket, key, payload);
                verifyStoredObject(key, candidate);
                return anchor;
            } catch (ExternalAuditAnchorSinkException exception) {
                if (!"CONFLICT".equals(exception.reason())) {
                    throw exception;
                }
                return idempotentExisting(key, client.getObject(bucket, key)
                        .orElseThrow(() -> new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.")), payload, candidate);
            }
        } catch (ExternalAuditAnchorSinkException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("External audit anchor object-store sink unavailable.");
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        }
    }

    @Override
    public Optional<ExternalAuditAnchor> latest(String partitionKey) {
        String keyPrefix = objectKeyPrefix(partitionKey);
        try {
            return client.listKeys(bucket, keyPrefix, LIST_LIMIT).stream()
                    .map(key -> read(key).orElse(null))
                    .filter(Objects::nonNull)
                    .max(Comparator.comparingLong(ExternalAuditAnchor::chainPosition)
                            .thenComparing(ExternalAuditAnchor::createdAt));
        } catch (ExternalAuditAnchorSinkException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        }
    }

    @Override
    public Optional<ExternalAuditAnchor> findByChainPosition(String partitionKey, long chainPosition) {
        try {
            return read(objectKey(partitionKey, chainPosition));
        } catch (ExternalAuditAnchorSinkException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        }
    }

    @Override
    public List<ExternalAuditAnchor> findByRange(String partitionKey, Instant from, Instant to, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, LIST_LIMIT));
        String keyPrefix = objectKeyPrefix(partitionKey);
        try {
            return client.listKeys(bucket, keyPrefix, boundedLimit).stream()
                    .map(key -> read(key).orElse(null))
                    .filter(Objects::nonNull)
                    .filter(anchor -> from == null || !anchor.createdAt().isBefore(from))
                    .filter(anchor -> to == null || !anchor.createdAt().isAfter(to))
                    .sorted(Comparator.comparingLong(ExternalAuditAnchor::chainPosition))
                    .limit(boundedLimit)
                    .toList();
        } catch (ExternalAuditAnchorSinkException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        }
    }

    String objectKey(String partitionKey, long chainPosition) {
        if (!StringUtils.hasText(partitionKey)) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor partition key is invalid.");
        }
        if (chainPosition < 0) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor chain position is invalid.");
        }
        return objectKeyPrefix(partitionKey) + "/" + chainPosition + ".json";
    }

    private String objectKeyPrefix(String partitionKey) {
        if (!StringUtils.hasText(partitionKey)) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor partition key is invalid.");
        }
        return prefix + "/" + encodePartitionKey(partitionKey);
    }

    private ExternalAuditAnchor idempotentExisting(
            String key,
            byte[] existing,
            byte[] candidate,
            ObjectStoreExternalAuditAnchorPayload expected
    ) {
        if (!Arrays.equals(existing, candidate)) {
            throw new ExternalAuditAnchorSinkException("MISMATCH", "External anchor object content mismatch.");
        }
        ObjectStoreExternalAuditAnchorPayload payload = readPayload(key, existing);
        verifyPayloadBinding(key, payload, expected);
        return payload.toExternalAnchor();
    }

    private Optional<ExternalAuditAnchor> read(String key) {
        return client.getObject(bucket, key).map(payload -> read(key, payload));
    }

    private ExternalAuditAnchor read(String key, byte[] payload) {
        return readPayload(key, payload).toExternalAnchor();
    }

    private ObjectStoreExternalAuditAnchorPayload readPayload(String key, byte[] payload) {
        try {
            ObjectStoreExternalAuditAnchorPayload anchor = objectMapper.readValue(payload, ObjectStoreExternalAuditAnchorPayload.class);
            verifyPayloadSelfBinding(key, anchor);
            return anchor;
        } catch (Exception exception) {
            if (exception instanceof ExternalAuditAnchorSinkException sinkException) {
                throw sinkException;
            }
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor object is unreadable.");
        }
    }

    private ObjectStoreExternalAuditAnchorPayload payload(ExternalAuditAnchor anchor, String key) {
        ObjectStoreExternalAuditAnchorPayload unsigned = ObjectStoreExternalAuditAnchorPayload.from(anchor, key, null);
        return ObjectStoreExternalAuditAnchorPayload.from(anchor, key, sha256Hex(serialize(unsigned)));
    }

    private byte[] serialize(ObjectStoreExternalAuditAnchorPayload anchor) {
        try {
            return objectMapper.writeValueAsBytes(anchor);
        } catch (JsonProcessingException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor object could not be serialized.");
        }
    }

    private void verifyStoredObject(String key, ObjectStoreExternalAuditAnchorPayload expected) {
        ObjectStoreExternalAuditAnchorPayload stored = client.getObject(bucket, key)
                .map(payload -> readPayload(key, payload))
                .orElseThrow(() -> new ExternalAuditAnchorSinkException("WRITE_NOT_VERIFIED", "External anchor write could not be verified."));
        verifyPayloadBinding(key, stored, expected);
    }

    private void verifyPayloadSelfBinding(String key, ObjectStoreExternalAuditAnchorPayload payload) {
        if (!key.equals(payload.externalObjectKey())) {
            throw new ExternalAuditAnchorSinkException("EXTERNAL_OBJECT_KEY_MISMATCH", "External anchor object key binding mismatch.");
        }
        String actualPayloadHash = sha256Hex(serialize(ObjectStoreExternalAuditAnchorPayload.from(
                payload.toExternalAnchor(),
                payload.externalObjectKey(),
                null
        )));
        if (!actualPayloadHash.equals(payload.payloadHash())) {
            throw new ExternalAuditAnchorSinkException("EXTERNAL_PAYLOAD_HASH_MISMATCH", "External anchor payload hash mismatch.");
        }
    }

    private void verifyPayloadBinding(
            String key,
            ObjectStoreExternalAuditAnchorPayload stored,
            ObjectStoreExternalAuditAnchorPayload expected
    ) {
        if (!key.equals(stored.externalObjectKey()) || !stored.externalObjectKey().equals(expected.externalObjectKey())) {
            throw new ExternalAuditAnchorSinkException("EXTERNAL_OBJECT_KEY_MISMATCH", "External anchor object key binding mismatch.");
        }
        if (!stored.payloadHash().equals(expected.payloadHash())) {
            throw new ExternalAuditAnchorSinkException("EXTERNAL_PAYLOAD_HASH_MISMATCH", "External anchor payload hash mismatch.");
        }
        if (!stored.localAnchorId().equals(expected.localAnchorId())
                || stored.chainPosition() != expected.chainPosition()
                || !stored.eventHash().equals(expected.eventHash())) {
            throw new ExternalAuditAnchorSinkException("MISMATCH", "External anchor local binding mismatch.");
        }
    }

    private void detectDuplicateAnchorConflict(ExternalAuditAnchor anchor, String targetKey) {
        for (String key : client.listKeys(bucket, objectKeyPrefix(anchor.partitionKey()), LIST_LIMIT)) {
            if (targetKey.equals(key)) {
                continue;
            }
            Optional<ExternalAuditAnchor> existing = read(key);
            if (existing.isPresent()
                    && anchor.localAnchorId().equals(existing.get().localAnchorId())
                    && (anchor.chainPosition() != existing.get().chainPosition()
                    || !anchor.lastEventHash().equals(existing.get().lastEventHash()))) {
                throw new ExternalAuditAnchorSinkException("MISMATCH", "External anchor local anchor id conflict.");
            }
        }
    }

    private String encodePartitionKey(String partitionKey) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(partitionKey.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor payload hash could not be computed.");
        }
    }

    private ObjectMapper canonicalMapper(ObjectMapper base) {
        ObjectMapper mapper = base == null ? JsonMapper.builder().build() : base.copy();
        mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Object-store audit anchor " + name + " is required.");
        }
        return value.trim();
    }

    private String trimSlashes(String value) {
        String trimmed = value;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
