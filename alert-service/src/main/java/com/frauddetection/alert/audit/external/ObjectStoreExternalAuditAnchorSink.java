package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

class ObjectStoreExternalAuditAnchorSink implements ExternalAuditAnchorSink {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreExternalAuditAnchorSink.class);
    private static final int LIST_LIMIT = 500;
    private static final int CHAIN_POSITION_WIDTH = 20;
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
    private final ExternalWitnessCapabilities capabilities;

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
        this(bucket, prefix, client, objectMapper, metrics, operationTimeout, retryBackoff, maxAttempts,
                immutabilityLevel, ExternalWitnessCapabilities.objectStore(immutabilityLevel));
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
            ExternalImmutabilityLevel immutabilityLevel,
            ExternalWitnessCapabilities capabilities
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
        this.capabilities = capabilities == null ? ExternalWitnessCapabilities.objectStore(this.immutabilityLevel) : capabilities;
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
    public ExternalWitnessCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public ExternalAuditAnchor publish(ExternalAuditAnchor anchor) {
        String key = objectKey(anchor.partitionKey(), anchor.chainPosition());
        ObjectStoreExternalAuditAnchorPayload candidate = payload(anchor, key);
        byte[] payload = serialize(candidate);
        try {
            Optional<byte[]> existing = getObject(key);
            if (existing.isPresent()) {
                return idempotentExisting(key, existing.get(), payload, candidate);
            }
            Optional<byte[]> legacyExisting = getObject(legacyObjectKey(anchor.partitionKey(), anchor.chainPosition()));
            if (legacyExisting.isPresent()) {
                return idempotentExisting(legacyObjectKey(anchor.partitionKey(), anchor.chainPosition()), legacyExisting.get(), payload, candidate);
            }
            rejectNonMonotonicNewAnchor(anchor);
            try {
                putObjectIfAbsent(key, payload);
                verifyStoredObject(key, candidate);
                return updateHeadManifest(anchor.published(null, null), key);
            } catch (ExternalAuditAnchorSinkException exception) {
                if ("CONFLICT".equals(exception.reason())) {
                    ExternalAuditAnchor stored = idempotentExisting(key, getObject(key)
                            .orElseThrow(() -> new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.")), payload, candidate);
                    return updateHeadManifest(stored, key);
                }
                if (isUnverifiedWrite(exception.reason())) {
                    recordFailure("get");
                    return anchor.unverified(exception.reason());
                }
                if (isInvalidWrite(exception.reason())) {
                    recordFailure("get");
                    return anchor.invalid(exception.reason());
                }
                throw exception;
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
        ObjectStoreAuditAnchorObject object = getObjectWithMetadata(key)
                .orElseThrow(() -> new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor object is unavailable."));
        ObjectStoreExternalAuditAnchorPayload payload = readPayload(key, object.content());
        TimestampEvidence timestamp = timestampEvidence(object);
        return Optional.of(new ExternalAnchorReference(
                anchor.localAnchorId(),
                key,
                anchor.lastEventHash(),
                payload.eventHash(),
                Instant.now(),
                timestamp.timestampValue(),
                timestamp.timestampType(),
                timestamp.timestampTrustLevel(),
                timestamp.timestampSource(),
                timestamp.timestampVerified(),
                capabilities().durabilityGuarantee()
        ));
    }

    @Override
    public Optional<ExternalAuditAnchor> latest(String partitionKey) {
        String keyPrefix = objectKeyPrefix(partitionKey);
        try {
            Optional<ExternalAuditAnchor> manifestHead = latestFromManifest(partitionKey, keyPrefix);
            if (manifestHead.isPresent()) {
                return manifestHead;
            }
            recordManifestFallbackScan();
            List<String> keys = scanAnchorKeys(keyPrefix);
            recordHeadScanDepth(keys.size());
            return keys.stream()
                    .max(Comparator.comparingLong(this::parseChainPosition)
                            .thenComparing(key -> key))
                    .flatMap(this::read);
        } catch (ExternalAuditAnchorSinkException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        }
    }

    @Override
    public Optional<ExternalAuditAnchor> findByChainPosition(String partitionKey, long chainPosition) {
        try {
            Optional<ExternalAuditAnchor> padded = read(objectKey(partitionKey, chainPosition));
            Optional<ExternalAuditAnchor> legacy = read(legacyObjectKey(partitionKey, chainPosition));
            if (padded.isPresent() && legacy.isPresent() && !sameImmutableAnchor(padded.get(), legacy.get())) {
                throw conflict(padded.get(), legacy.get());
            }
            return padded.or(() -> legacy);
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
        return objectKeyPrefix(partitionKey) + "/" + formatChainPosition(chainPosition) + ".json";
    }

    String formatChainPosition(long position) {
        if (position < 0) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor chain position is invalid.");
        }
        return String.format("%0" + CHAIN_POSITION_WIDTH + "d", position);
    }

    long parseChainPosition(String key) {
        String filename = key.substring(key.lastIndexOf('/') + 1);
        if (!filename.endsWith(".json")) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor object key is invalid.");
        }
        String value = filename.substring(0, filename.length() - ".json".length());
        if (value.isBlank() || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor object key is invalid.");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor chain position is invalid.");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor chain position is invalid.");
        }
    }

    private String legacyObjectKey(String partitionKey, long chainPosition) {
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
        ObjectStoreExternalAuditAnchorPayload payload = readPayload(key, existing);
        verifyPayloadBinding(key, payload, expected);
        return payload.toExternalAnchor();
    }

    private synchronized ExternalAuditAnchor updateHeadManifest(ExternalAuditAnchor anchor, String anchorKey) {
        try {
            Optional<ObjectStoreExternalAuditHeadManifest> current = readHeadManifestForUpdate(anchor.partitionKey());
            if (current.isPresent()) {
                long currentPosition = current.get().latestChainPosition();
                if (currentPosition > anchor.chainPosition()) {
                    return anchor;
                }
                if (currentPosition == anchor.chainPosition()
                        && anchorKey.equals(current.get().latestExternalKey())
                        && anchor.localAnchorId().equals(current.get().latestAnchorId())) {
                    return anchor;
                }
            }
            ObjectStoreExternalAuditHeadManifest manifest = signedManifest(anchor, anchorKey);
            putObject(headManifestKey(anchor.partitionKey()), serialize(manifest));
            ObjectStoreExternalAuditHeadManifest stored = readHeadManifest(objectKeyPrefix(anchor.partitionKey()))
                    .orElseThrow(() -> new ExternalAuditAnchorSinkException("HEAD_MANIFEST_UPDATE_FAILED", "External anchor head manifest is unavailable."));
            verifyHeadManifest(stored, anchor.partitionKey())
                    .orElseThrow(() -> new ExternalAuditAnchorSinkException("HEAD_MANIFEST_UPDATE_FAILED", "External anchor head manifest does not reference an available anchor."));
            recordManifestUpdate("SUCCESS");
            return anchor;
        } catch (ExternalAuditAnchorSinkException exception) {
            recordManifestUpdate("FAILED");
            log.warn("External audit anchor head manifest update failed: reason={}", exception.reason());
            return anchor.published(ExternalAuditAnchor.REASON_HEAD_MANIFEST_UPDATE_FAILED, ExternalAuditAnchor.MANIFEST_STATUS_FAILED);
        }
    }

    private Optional<ObjectStoreExternalAuditHeadManifest> readHeadManifestForUpdate(String partitionKey) {
        Optional<ObjectStoreExternalAuditHeadManifest> current;
        try {
            current = readHeadManifest(objectKeyPrefix(partitionKey));
        } catch (ExternalAuditAnchorSinkException exception) {
            recordManifestInvalid();
            return Optional.empty();
        }
        if (current.isPresent() && verifyHeadManifest(current.get(), partitionKey).isEmpty()) {
            recordManifestInvalid();
            return Optional.empty();
        }
        return current;
    }

    private Optional<ExternalAuditAnchor> latestFromManifest(String partitionKey, String keyPrefix) {
        Optional<ObjectStoreExternalAuditHeadManifest> manifest;
        try {
            manifest = readHeadManifest(keyPrefix);
        } catch (ExternalAuditAnchorSinkException exception) {
            recordManifestRead("INVALID");
            recordManifestInvalid();
            return Optional.empty();
        }
        if (manifest.isEmpty()) {
            recordManifestRead("MISS");
            return Optional.empty();
        }
        Optional<ExternalAuditAnchor> verified = verifyHeadManifest(manifest.get(), partitionKey);
        if (verified.isPresent()) {
            recordManifestRead("HIT");
            return verified;
        }
        recordManifestRead("INVALID");
        recordManifestInvalid();
        return Optional.empty();
    }

    private Optional<ObjectStoreExternalAuditHeadManifest> readHeadManifest(String keyPrefix) {
        Optional<byte[]> payload = getObject(keyPrefix + "/head.json");
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload.get(), ObjectStoreExternalAuditHeadManifest.class));
        } catch (Exception exception) {
            throw new ExternalAuditAnchorSinkException("HEAD_MANIFEST_INVALID", "External anchor head manifest is unreadable.");
        }
    }

    private Optional<ExternalAuditAnchor> verifyHeadManifest(
            ObjectStoreExternalAuditHeadManifest manifest,
            String partitionKey
    ) {
        if (manifest.manifestHash() == null
                || !manifest.manifestHash().equals(sha256Hex(serialize(manifest.withoutManifestHash())))
                || !partitionKey.equals(manifest.partitionKey())
                || manifest.latestChainPosition() < 0
                || !StringUtils.hasText(manifest.latestExternalKey())) {
            return Optional.empty();
        }
        Optional<ExternalAuditAnchor> referenced = read(manifest.latestExternalKey());
        if (referenced.isEmpty()) {
            recordManifestMismatch();
            return Optional.empty();
        }
        ExternalAuditAnchor anchor = referenced.get();
        if (anchor.chainPosition() != manifest.latestChainPosition()
                || !anchor.localAnchorId().equals(manifest.latestAnchorId())
                || !anchor.lastEventHash().equals(manifest.latestEventHash())
                || !anchor.partitionKey().equals(manifest.partitionKey())) {
            recordManifestMismatch();
            return Optional.empty();
        }
        return Optional.of(anchor);
    }

    private ObjectStoreExternalAuditHeadManifest signedManifest(ExternalAuditAnchor anchor, String anchorKey) {
        ObjectStoreExternalAuditHeadManifest unsigned = ObjectStoreExternalAuditHeadManifest.unsigned(anchor, anchorKey, Instant.now());
        return unsigned.withManifestHash(sha256Hex(serialize(unsigned)));
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

    private byte[] serialize(ObjectStoreExternalAuditHeadManifest manifest) {
        try {
            return objectMapper.writeValueAsBytes(manifest);
        } catch (JsonProcessingException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor head manifest could not be serialized.");
        }
    }

    private void verifyStoredObject(String key, ObjectStoreExternalAuditAnchorPayload expected) {
        ObjectStoreExternalAuditAnchorPayload stored = getObject(key)
                .map(payload -> readPayload(key, payload))
                .orElseThrow(() -> new ExternalAuditAnchorSinkException("WRITE_NOT_VERIFIED", "External anchor write could not be verified."));
        verifyPayloadBinding(key, stored, expected);
    }

    private void verifyPayloadSelfBinding(String key, ObjectStoreExternalAuditAnchorPayload payload) {
        if (payload.anchorIdVersion() != ExternalAuditAnchor.ANCHOR_ID_VERSION) {
            throw tampering("EXTERNAL_ANCHOR_ID_VERSION_UNSUPPORTED", "External anchor id version is unsupported.");
        }
        String expectedAnchorId = ExternalAuditAnchor.deterministicAnchorId(
                payload.source(),
                payload.schemaVersion(),
                payload.partitionKey(),
                payload.chainPosition(),
                payload.eventHash()
        );
        if (!expectedAnchorId.equals(payload.anchorId())) {
            throw tampering("EXTERNAL_ANCHOR_ID_MISMATCH", "External anchor id binding mismatch.");
        }
        if (!key.equals(payload.externalObjectKey())) {
            throw tampering("EXTERNAL_OBJECT_KEY_MISMATCH", "External anchor object key binding mismatch.");
        }
        String actualPayloadHash = sha256Hex(serialize(payload.withoutPayloadHash()));
        if (!actualPayloadHash.equals(payload.payloadHash())) {
            throw tampering("EXTERNAL_PAYLOAD_HASH_MISMATCH", "External anchor payload hash mismatch.");
        }
    }

    private void rejectNonMonotonicNewAnchor(ExternalAuditAnchor anchor) {
        Optional<ObjectStoreExternalAuditHeadManifest> current = readHeadManifestForUpdate(anchor.partitionKey());
        if (current.isEmpty()) {
            return;
        }
        long currentPosition = current.get().latestChainPosition();
        if (currentPosition > anchor.chainPosition()) {
            throw new ExternalAuditAnchorConflictException(
                    List.of(current.get().latestEventHash(), anchor.lastEventHash()),
                    List.of("head-manifest", anchor.sinkType())
            );
        }
        if (currentPosition == anchor.chainPosition()
                && (!anchor.localAnchorId().equals(current.get().latestAnchorId())
                || !anchor.lastEventHash().equals(current.get().latestEventHash()))) {
            throw new ExternalAuditAnchorConflictException(
                    List.of(current.get().latestEventHash(), anchor.lastEventHash()),
                    List.of("head-manifest", anchor.sinkType())
            );
        }
    }

    private void verifyPayloadBinding(
            String key,
            ObjectStoreExternalAuditAnchorPayload stored,
            ObjectStoreExternalAuditAnchorPayload expected
    ) {
        if (!key.equals(stored.externalObjectKey()) || !stored.externalObjectKey().equals(expected.externalObjectKey())) {
            if (stored.chainPosition() == expected.chainPosition()) {
                if (stored.localAnchorId().equals(expected.localAnchorId())
                        && stored.eventHash().equals(expected.eventHash())) {
                    return;
                }
                throw new ExternalAuditAnchorConflictException(
                        List.of(stored.eventHash(), expected.eventHash()),
                        List.of(stored.source(), expected.source())
                );
            }
            throw tampering("EXTERNAL_OBJECT_KEY_MISMATCH", "External anchor object key binding mismatch.");
        }
        if (stored.anchorIdVersion() != expected.anchorIdVersion()
                || !stored.anchorId().equals(expected.anchorId())
                || !stored.localAnchorId().equals(expected.localAnchorId())
                || stored.chainPosition() != expected.chainPosition()
                || !stored.eventHash().equals(expected.eventHash())) {
            throw new ExternalAuditAnchorConflictException(
                    List.of(stored.eventHash(), expected.eventHash()),
                    List.of(stored.source(), expected.source())
            );
        }
        if (!stored.payloadHash().equals(expected.payloadHash())) {
            throw tampering("EXTERNAL_PAYLOAD_HASH_MISMATCH", "External anchor payload hash mismatch.");
        }
    }

    private Optional<byte[]> getObject(String key) {
        return callWithRetry("get", () -> client.getObject(bucket, key));
    }

    private Optional<ObjectStoreAuditAnchorObject> getObjectWithMetadata(String key) {
        return callWithRetry("get", () -> client.getObjectWithMetadata(bucket, key));
    }

    private void putObjectIfAbsent(String key, byte[] payload) {
        callWithRetry("put", () -> {
            client.putObjectIfAbsent(bucket, key, payload);
            return null;
        });
    }

    private void putObject(String key, byte[] payload) {
        callWithRetry("put", () -> {
            client.putObject(bucket, key, payload);
            return null;
        });
    }

    private List<String> listKeys(String keyPrefix, int limit) {
        return callWithRetry("list", () -> client.listKeys(bucket, keyPrefix, limit));
    }

    private ObjectStoreAuditAnchorKeyPage listKeysPage(String keyPrefix, int limit, String continuationToken) {
        return callWithRetry("list", () -> client.listKeysPage(bucket, keyPrefix, limit, continuationToken));
    }

    private List<String> scanAnchorKeys(String keyPrefix) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        java.util.Map<Long, ExternalAuditAnchor> anchorsByPosition = new java.util.HashMap<>();
        Set<String> seenContinuationTokens = new HashSet<>();
        String continuationToken = null;
        do {
            ObjectStoreAuditAnchorKeyPage page = listKeysPage(keyPrefix, LIST_LIMIT, continuationToken);
            keys.addAll(page.keys().stream()
                    .filter(key -> {
                        try {
                            parseChainPosition(key);
                            return true;
                        } catch (ExternalAuditAnchorSinkException exception) {
                            return false;
                        }
                    })
                    .toList());
            for (String key : page.keys()) {
                try {
                    long chainPosition = parseChainPosition(key);
                    Optional<ExternalAuditAnchor> anchor = read(key);
                    if (anchor.isPresent()) {
                        ExternalAuditAnchor previous = anchorsByPosition.putIfAbsent(chainPosition, anchor.get());
                        if (previous != null && !sameImmutableAnchor(previous, anchor.get())) {
                            throw conflict(previous, anchor.get());
                        }
                    }
                } catch (ExternalAuditAnchorSinkException exception) {
                    if (!"INVALID_ANCHOR".equals(exception.reason())) {
                        throw exception;
                    }
                }
            }
            /*
             * HEAD detection must consume every page because legacy non-padded keys do not sort
             * by numeric chain position. A client without continuation-token semantics must fail
             * explicitly instead of returning a best-effort HEAD from a truncated listing.
             */
            continuationToken = page.nextContinuationToken();
            if (continuationToken != null && !seenContinuationTokens.add(continuationToken)) {
                throw new ExternalAuditAnchorSinkException(
                        "HEAD_SCAN_LIMIT_EXCEEDED",
                        "External anchor head scan pagination did not make progress."
                );
            }
        } while (continuationToken != null);
        return List.copyOf(keys);
    }

    private boolean sameImmutableAnchor(ExternalAuditAnchor left, ExternalAuditAnchor right) {
        return Objects.equals(left.localAnchorId(), right.localAnchorId())
                && Objects.equals(left.partitionKey(), right.partitionKey())
                && left.chainPosition() == right.chainPosition()
                && Objects.equals(left.lastEventHash(), right.lastEventHash())
                && Objects.equals(left.previousEventHash(), right.previousEventHash())
                && Objects.equals(left.hashAlgorithm(), right.hashAlgorithm())
                && Objects.equals(left.schemaVersion(), right.schemaVersion());
    }

    private ExternalAuditAnchorConflictException conflict(ExternalAuditAnchor left, ExternalAuditAnchor right) {
        return new ExternalAuditAnchorConflictException(
                List.of(left.lastEventHash(), right.lastEventHash()),
                List.of(left.sinkType(), right.sinkType())
        );
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

    private void recordHeadScanDepth(int scannedKeys) {
        if (metrics != null) {
            metrics.recordExternalAnchorHeadScanDepth(scannedKeys);
        }
    }

    private void recordManifestRead(String status) {
        if (metrics != null) {
            metrics.recordExternalManifestRead(status);
        }
    }

    private void recordManifestUpdate(String status) {
        if (metrics != null) {
            metrics.recordExternalManifestUpdate(status);
        }
    }

    private void recordManifestFallbackScan() {
        if (metrics != null) {
            metrics.recordExternalManifestFallbackScan();
        }
    }

    private void recordManifestInvalid() {
        if (metrics != null) {
            metrics.recordExternalManifestInvalid();
        }
    }

    private void recordManifestMismatch() {
        if (metrics != null) {
            metrics.recordExternalManifestMismatch();
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

    private String headManifestKey(String partitionKey) {
        return objectKeyPrefix(partitionKey) + "/head.json";
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
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        return mapper;
    }

    private boolean isUnverifiedWrite(String reason) {
        return "WRITE_NOT_VERIFIED".equals(reason)
                || "IO_ERROR".equals(reason)
                || "TIMEOUT".equals(reason);
    }

    private boolean isInvalidWrite(String reason) {
        return "EXTERNAL_OBJECT_KEY_MISMATCH".equals(reason)
                || "EXTERNAL_PAYLOAD_HASH_MISMATCH".equals(reason)
                || "EXTERNAL_ANCHOR_ID_MISMATCH".equals(reason)
                || "EXTERNAL_ANCHOR_ID_VERSION_UNSUPPORTED".equals(reason)
                || "MISMATCH".equals(reason);
    }

    private TimestampEvidence timestampEvidence(ObjectStoreAuditAnchorObject object) {
        if (object != null
                && object.timestampVerified()
                && object.timestampValue() != null
                && object.timestampType() != null
                && object.timestampType() != ExternalWitnessTimestampType.APP_OBSERVED) {
            return new TimestampEvidence(
                    object.timestampValue(),
                    object.timestampType(),
                    capabilities.timestampTrustLevel(),
                    StringUtils.hasText(object.timestampSource()) ? object.timestampSource() : "WITNESS_METADATA",
                    true
            );
        }
        return new TimestampEvidence(
                null,
                ExternalWitnessTimestampType.APP_OBSERVED,
                ExternalTimestampTrustLevel.WEAK.name(),
                "APP_CLOCK",
                false
        );
    }

    private record TimestampEvidence(
            Instant timestampValue,
            ExternalWitnessTimestampType timestampType,
            String timestampTrustLevel,
            String timestampSource,
            boolean timestampVerified
    ) {
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
