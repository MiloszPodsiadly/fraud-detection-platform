package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.AuditAnchorDocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public record ExternalAuditAnchor(
        @JsonProperty("external_anchor_id")
        String externalAnchorId,

        @JsonProperty("local_anchor_id")
        String localAnchorId,

        @JsonProperty("partition_key")
        String partitionKey,

        @JsonProperty("chain_position")
        long chainPosition,

        @JsonProperty("last_event_hash")
        String lastEventHash,

        @JsonProperty("previous_event_hash")
        String previousEventHash,

        @JsonProperty("hash_algorithm")
        String hashAlgorithm,

        @JsonProperty("schema_version")
        String schemaVersion,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("sink_type")
        String sinkType,

        @JsonProperty("publication_status")
        String publicationStatus,

        @JsonProperty("publication_reason")
        String publicationReason,

        @JsonProperty("manifest_status")
        String manifestStatus
) {
    public static final String SCHEMA_VERSION = "1.0";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String REASON_HEAD_MANIFEST_UPDATE_FAILED = "HEAD_MANIFEST_UPDATE_FAILED";
    public static final String MANIFEST_STATUS_FAILED = "FAILED";

    public ExternalAuditAnchor(
            String externalAnchorId,
            String localAnchorId,
            String partitionKey,
            long chainPosition,
            String lastEventHash,
            String hashAlgorithm,
            String schemaVersion,
            Instant createdAt,
            String sinkType,
            String publicationStatus
    ) {
        this(
                externalAnchorId,
                localAnchorId,
                partitionKey,
                chainPosition,
                lastEventHash,
                null,
                hashAlgorithm,
                schemaVersion,
                createdAt,
                sinkType,
                publicationStatus,
                null,
                null
        );
    }

    public static ExternalAuditAnchor from(AuditAnchorDocument localAnchor, String sinkType) {
        String schemaVersion = SCHEMA_VERSION;
        return new ExternalAuditAnchor(
                deterministicAnchorId(sinkType, schemaVersion, localAnchor.partitionKey(), localAnchor.chainPosition(), localAnchor.lastEventHash()),
                localAnchor.anchorId(),
                localAnchor.partitionKey(),
                localAnchor.chainPosition(),
                localAnchor.lastEventHash(),
                localAnchor.previousEventHash(),
                localAnchor.hashAlgorithm(),
                schemaVersion,
                localAnchor.createdAt(),
                sinkType,
                STATUS_PUBLISHED,
                null,
                null
        );
    }

    ExternalAuditAnchor partial() {
        return partial(REASON_HEAD_MANIFEST_UPDATE_FAILED, MANIFEST_STATUS_FAILED);
    }

    ExternalAuditAnchor partial(String reason, String manifestStatus) {
        return new ExternalAuditAnchor(
                externalAnchorId,
                localAnchorId,
                partitionKey,
                chainPosition,
                lastEventHash,
                previousEventHash,
                hashAlgorithm,
                schemaVersion,
                createdAt,
                sinkType,
                STATUS_PARTIAL,
                reason,
                manifestStatus
        );
    }

    private static String deterministicAnchorId(String source, String schemaVersion, String partitionKey, long chainPosition, String eventHash) {
        String canonical = nullToEmpty(source)
                + ":" + nullToEmpty(schemaVersion)
                + ":" + nullToEmpty(partitionKey)
                + ":" + chainPosition
                + ":" + nullToEmpty(eventHash);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("External anchor id could not be computed.", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
