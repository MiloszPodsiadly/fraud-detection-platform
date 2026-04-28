package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

class ObjectStoreExternalAuditAnchorSink implements ExternalAuditAnchorSink {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreExternalAuditAnchorSink.class);
    private static final int LIST_LIMIT = 500;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_BACKOFF = Duration.ofMillis(100);
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    private final String bucket;
    private final String prefix;
    private final ObjectStoreAuditAnchorClient client;
    private final ObjectMapper objectMapper;
    private final AlertServiceMetrics metrics;
    private final Duration operationTimeout;
    private final Duration retryBackoff;
    private final int maxAttempts;
    private final ExternalImmutabilityLevel immutabilityLevel;

    ObjectStoreExternalAuditAnchorSink(
            String bucket,
            String prefix,
            ObjectStoreAuditAnchorClient client,
            ObjectMapper objectMapper
    ) {
        this(bucket, prefix, client, objectMapper, null, DEFAULT_TIMEOUT, DEFAULT_BACKOFF, DEFAULT_MAX_ATTEMPTS, ExternalImmutabilityLevel.NONE);
    }

    ObjectStoreExternalAuditAnchorSink(
            String bucket,
            String prefix,
            ObjectStoreAuditAnchorClient client,
            ObjectMapper objectMapper,
            AlertServiceMetrics metrics,
            Duration operationTimeout,
            Duration retryBackoff,
            int maxAttempts,
            ExternalImmutabilityLevel immutabilityLevel
    ) {
        this.bucket = requireText(bucket, "bucket");
        this.prefix = trimSlashes(requireText(prefix, "prefix"));
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper = canonicalMapper(objectMapper);
        this.metrics = metrics;
        this.operationTimeout = operationTimeout == null || operationTimeout.isNegative() || operationTimeout.isZero()
                ? DEFAULT_TIMEOUT
                : operationTimeout;
        this.retryBackoff = retryBackoff == null || retryBackoff.isNegative()
                ? DEFAULT_BACKOFF
                : retryBackoff;
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 3));
        this.immutabilityLevel = immutabilityLevel == null ? ExternalImmutabilityLevel.NONE : immutabilityLevel;
    }

    @Override
    public String sinkType() {
        return "object-store";
    }

    @Override
    public ExternalImmutabilityLevel immutabilityLevel() {
        return immutabilityLevel;
    }

    @Override
    public ExternalAuditAnchor publish(ExternalAuditAnchor anchor) {
        String key = objectKey(anchor.partitionKey(), anchor.chainPosition());
        ObjectStoreExternalAuditAnchorPayload candidate = payload(anchor, key);
        byte[] payload = serialize(candidate);
        try {
            detectDuplicateAnchorConflict(anchor, key);
            Optional<byte[]> existing = getObject(key);
            if (existing.isPresent()) {
                return idempotentExisting(key, existing.get(), payload, candidate);
            }
            try {
                putObjectIfAbsent(key, payload);
                verifyStoredObject(key, candidate);
                return anchor;
            } catch (ExternalAuditAnchorSinkException exception) {
                if (!"CONFLICT".equals(exception.reason())) {
                    throw exception;
                }
                return idempotentExisting(key, getObject(key)
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
    public Optional<ExternalAnchorReference> externalReference(ExternalAuditAnchor anchor) {
        if (anchor == null) {
            return Optional.empty();
        }
        String key = objectKey(anchor.partitionKey(), anchor.chainPosition());
        ObjectStoreExternalAuditAnchorPayload payload = readPayload(key, getObject(key)
                .orElseThrow(() -> new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor object is unavailable.")));
        return Optional.of(new ExternalAnchorReference(
                anchor.localAnchorId(),
                key,
                anchor.lastEventHash(),
                payload.eventHash(),
                Instant.now()
        ));
    }

    @Override
    public Optional<ExternalAuditAnchor> latest(String partitionKey) {
        String keyPrefix = objectKeyPrefix(partitionKey);
        try {
            return listKeys(keyPrefix, LIST_LIMIT).stream()
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
            return listKeys(keyPrefix, boundedLimit).stream()
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
        return getObject(key).map(payload -> read(key, payload));
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
        ObjectStoreExternalAuditAnchorPayload stored = getObject(key)
                .map(payload -> readPayload(key, payload))
                .orElseThrow(() -> new ExternalAuditAnchorSinkException("WRITE_NOT_VERIFIED", "External anchor write could not be verified."));
        verifyPayloadBinding(key, stored, expected);
    }

    private void verifyPayloadSelfBinding(String key, ObjectStoreExternalAuditAnchorPayload payload) {
        if (!key.equals(payload.externalObjectKey())) {
            throw tampering("EXTERNAL_OBJECT_KEY_MISMATCH", "External anchor object key binding mismatch.");
        }
        String actualPayloadHash = sha256Hex(serialize(ObjectStoreExternalAuditAnchorPayload.from(
                payload.toExternalAnchor(),
                payload.externalObjectKey(),
                null
        )));
        if (!actualPayloadHash.equals(payload.payloadHash())) {
            throw tampering("EXTERNAL_PAYLOAD_HASH_MISMATCH", "External anchor payload hash mismatch.");
        }
    }

    private void verifyPayloadBinding(
            String key,
            ObjectStoreExternalAuditAnchorPayload stored,
            ObjectStoreExternalAuditAnchorPayload expected
    ) {
        if (!key.equals(stored.externalObjectKey()) || !stored.externalObjectKey().equals(expected.externalObjectKey())) {
            throw tampering("EXTERNAL_OBJECT_KEY_MISMATCH", "External anchor object key binding mismatch.");
        }
        if (!stored.payloadHash().equals(expected.payloadHash())) {
            throw tampering("EXTERNAL_PAYLOAD_HASH_MISMATCH", "External anchor payload hash mismatch.");
        }
        if (!stored.localAnchorId().equals(expected.localAnchorId())
                || stored.chainPosition() != expected.chainPosition()
                || !stored.eventHash().equals(expected.eventHash())) {
            throw new ExternalAuditAnchorSinkException("MISMATCH", "External anchor local binding mismatch.");
        }
    }

    private void detectDuplicateAnchorConflict(ExternalAuditAnchor anchor, String targetKey) {
        for (String key : listKeys(objectKeyPrefix(anchor.partitionKey()), LIST_LIMIT)) {
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

    private Optional<byte[]> getObject(String key) {
        return callWithRetry("get", () -> client.getObject(bucket, key));
    }

    private void putObjectIfAbsent(String key, byte[] payload) {
        callWithRetry("put", () -> {
            client.putObjectIfAbsent(bucket, key, payload);
            return null;
        });
    }

    private List<String> listKeys(String keyPrefix, int limit) {
        return callWithRetry("list", () -> client.listKeys(bucket, keyPrefix, limit));
    }

    private <T> T callWithRetry(String operation, Supplier<T> operationCall) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callWithTimeout(operation, operationCall);
            } catch (ExternalAuditAnchorSinkException exception) {
                last = exception;
                if ("CONFLICT".equals(exception.reason()) || "MISMATCH".equals(exception.reason())) {
                    throw exception;
                }
            } catch (RuntimeException exception) {
                last = exception;
            }
            if (attempt < maxAttempts) {
                recordRetry(operation);
                backoff();
            }
        }
        recordFailure(operation);
        if (last instanceof ExternalAuditAnchorSinkException sinkException) {
            throw sinkException;
        }
        throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
    }

    private <T> T callWithTimeout(String operation, Supplier<T> operationCall) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(operationCall);
        try {
            return future.get(operationTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            recordTimeout(operation);
            throw new ExternalAuditAnchorSinkException("TIMEOUT", "External anchor sink operation timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ExternalAuditAnchorSinkException sinkException) {
                throw sinkException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        }
    }

    private ExternalAuditAnchorSinkException tampering(String reason, String message) {
        recordFailure("get");
        return new ExternalAuditAnchorSinkException(reason, message);
    }

    private void recordRetry(String operation) {
        if (metrics != null) {
            metrics.recordExternalAnchorOperationRetry(operation);
        }
    }

    private void recordTimeout(String operation) {
        if (metrics != null) {
            metrics.recordExternalAnchorOperationTimeout(operation);
        }
    }

    private void recordFailure(String operation) {
        if (metrics != null) {
            metrics.recordExternalAnchorOperationFailure(operation);
        }
    }

    private void backoff() {
        if (retryBackoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(retryBackoff.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
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
