package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

class LocalFileExternalAuditAnchorSink implements ExternalAuditAnchorSink {

    private static final Logger log = LoggerFactory.getLogger(LocalFileExternalAuditAnchorSink.class);

    private final Path path;
    private final ObjectMapper objectMapper;

    LocalFileExternalAuditAnchorSink(Path path, ObjectMapper objectMapper) {
        this.path = path;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sinkType() {
        return "local-file";
    }

    @Override
    public ExternalWitnessCapabilities capabilities() {
        return ExternalWitnessCapabilities.localFile();
    }

    @Override
    public synchronized ExternalAuditAnchor publish(ExternalAuditAnchor anchor) {
        try {
            Optional<ExternalAuditAnchor> existing = findByLocalAnchorId(anchor.localAnchorId());
            if (existing.isPresent()) {
                if (!sameImmutableAnchor(existing.get(), anchor)) {
                    throw new ExternalAuditAnchorSinkException("CONFLICT", "External anchor idempotency conflict.");
                }
                return existing.get();
            }
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    path,
                    objectMapper.writeValueAsString(anchor) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
            return anchor;
        } catch (ExternalAuditAnchorSinkException exception) {
            throw exception;
        } catch (IOException exception) {
            log.warn("External audit anchor local-file sink unavailable.");
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        }
    }

    @Override
    public Optional<ExternalAnchorReference> externalReference(ExternalAuditAnchor anchor) {
        if (anchor == null) {
            return Optional.empty();
        }
        ExternalAuditAnchor stored = findByLocalAnchorId(anchor.localAnchorId())
                .orElseThrow(() -> new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor is unavailable."));
        if (!sameImmutableAnchor(stored, anchor)) {
            throw new ExternalAuditAnchorSinkException("CONFLICT", "External anchor idempotency conflict.");
        }
        String externalKey = "local-file:" + path.toAbsolutePath().getFileName() + "#" + stored.chainPosition();
        return Optional.of(new ExternalAnchorReference(
                stored.localAnchorId(),
                externalKey,
                stored.lastEventHash(),
                stored.lastEventHash(),
                Instant.now()
        ));
    }

    @Override
    public Optional<ExternalAuditAnchor> latest(String partitionKey) {
        return readAll().stream()
                .filter(anchor -> Objects.equals(anchor.partitionKey(), partitionKey))
                .max(Comparator.comparingLong(ExternalAuditAnchor::chainPosition)
                        .thenComparing(ExternalAuditAnchor::createdAt));
    }

    @Override
    public List<ExternalAuditAnchor> findByRange(String partitionKey, Instant from, Instant to, int limit) {
        return readAll().stream()
                .filter(anchor -> Objects.equals(anchor.partitionKey(), partitionKey))
                .filter(anchor -> from == null || !anchor.createdAt().isBefore(from))
                .filter(anchor -> to == null || !anchor.createdAt().isAfter(to))
                .sorted(Comparator.comparingLong(ExternalAuditAnchor::chainPosition))
                .limit(limit)
                .toList();
    }

    private Optional<ExternalAuditAnchor> findByLocalAnchorId(String localAnchorId) {
        return readAll().stream()
                .filter(anchor -> Objects.equals(anchor.localAnchorId(), localAnchorId))
                .findFirst();
    }

    private List<ExternalAuditAnchor> readAll() {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(this::readAnchor)
                    .toList();
        } catch (IOException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink is unavailable.");
        }
    }

    private ExternalAuditAnchor readAnchor(String line) {
        try {
            return objectMapper.readValue(line, ExternalAuditAnchor.class);
        } catch (JsonProcessingException exception) {
            throw new ExternalAuditAnchorSinkException("IO_ERROR", "External anchor sink contains unreadable data.");
        }
    }

    private boolean sameImmutableAnchor(ExternalAuditAnchor left, ExternalAuditAnchor right) {
        return Objects.equals(left.localAnchorId(), right.localAnchorId())
                && Objects.equals(left.partitionKey(), right.partitionKey())
                && left.chainPosition() == right.chainPosition()
                && Objects.equals(left.lastEventHash(), right.lastEventHash())
                && Objects.equals(left.hashAlgorithm(), right.hashAlgorithm())
                && Objects.equals(left.schemaVersion(), right.schemaVersion())
                && Objects.equals(left.sinkType(), right.sinkType());
    }
}
