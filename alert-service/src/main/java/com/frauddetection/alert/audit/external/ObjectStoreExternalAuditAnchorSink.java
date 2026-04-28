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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
        byte[] payload = payload(anchor);
        try {
            Optional<byte[]> existing = client.getObject(bucket, key);
            if (existing.isPresent()) {
                return idempotentExisting(existing.get(), payload);
            }
            try {
                client.putObjectIfAbsent(bucket, key, payload);
                return anchor;
            } catch (ExternalAuditAnchorSinkException exception) {
                if (!"CONFLICT".equals(exception.reason())) {
                    throw exception;
                }
                return idempotentExisting(client.getObject(bucket, key)
                        .orElseThrow(() -> new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.")), payload);
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
        if (!StringUtils.hasText(partitionKey)
                || partitionKey.contains("/")
                || partitionKey.contains("\\")
                || partitionKey.contains("..")) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor partition key is invalid.");
        }
        if (chainPosition < 0) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor chain position is invalid.");
        }
        return objectKeyPrefix(partitionKey) + "/" + chainPosition + ".json";
    }

    private String objectKeyPrefix(String partitionKey) {
        if (!StringUtils.hasText(partitionKey)
                || partitionKey.contains("/")
                || partitionKey.contains("\\")
                || partitionKey.contains("..")) {
            throw new ExternalAuditAnchorSinkException("INVALID_ANCHOR", "External anchor partition key is invalid.");
        }
        return prefix + "/" + partitionKey;
    }

    private ExternalAuditAnchor idempotentExisting(byte[] existing, byte[] candidate) {
        if (!Arrays.equals(existing, candidate)) {
            throw new ExternalAuditAnchorSinkException("MISMATCH", "External anchor object content mismatch.");
        }
        return read(existing);
    }

    private Optional<ExternalAuditAnchor> read(String key) {
        return client.getObject(bucket, key).map(this::read);
    }

    private ExternalAuditAnchor read(byte[] payload) {
        try {
            return objectMapper.readValue(payload, ObjectStoreExternalAuditAnchorPayload.class).toExternalAnchor();
        } catch (Exception exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor object is unreadable.");
        }
    }

    private byte[] payload(ExternalAuditAnchor anchor) {
        try {
            return objectMapper.writeValueAsBytes(ObjectStoreExternalAuditAnchorPayload.from(anchor));
        } catch (JsonProcessingException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor object could not be serialized.");
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
