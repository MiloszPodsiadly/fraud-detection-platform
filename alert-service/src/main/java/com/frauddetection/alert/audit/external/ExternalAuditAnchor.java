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

        @JsonProperty("anchor_id_version")
        int anchorIdVersion,

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
    public static final int ANCHOR_ID_VERSION = 1;
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_UNVERIFIED = "UNVERIFIED";
    public static final String STATUS_LOCAL_STATUS_UNVERIFIED = "LOCAL_STATUS_UNVERIFIED";
    public static final String STATUS_LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED = "LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_INVALID = "INVALID";
    public static final String STATUS_CONFLICT = "CONFLICT";
    public static final String STATUS_MISSING = "MISSING";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    @Deprecated
    public static final String STATUS_PARTIAL = STATUS_UNVERIFIED;
    public static final String REASON_HEAD_MANIFEST_UPDATE_FAILED = "HEAD_MANIFEST_UPDATE_FAILED";
    public static final String REASON_STATUS_PERSISTENCE_FAILED_AFTER_EXTERNAL_PUBLISH = "STATUS_PERSISTENCE_FAILED_AFTER_EXTERNAL_PUBLISH";
    public static final String REASON_EXTERNAL_ANCHOR_REQUIRED_FAILED = "EXTERNAL_ANCHOR_REQUIRED_FAILED";
    public static final String MANIFEST_STATUS_FAILED = "FAILED";

    public ExternalAuditAnchor {
        anchorIdVersion = anchorIdVersion <= 0 ? ANCHOR_ID_VERSION : anchorIdVersion;
    }

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
                ANCHOR_ID_VERSION,
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
                ANCHOR_ID_VERSION,
                localAnchor.anchorId(),
                localAnchor.partitionKey(),
                localAnchor.chainPosition(),
                localAnchor.lastEventHash(),
                localAnchor.previousEventHash(),
                localAnchor.hashAlgorithm(),
                schemaVersion,
                localAnchor.createdAt(),
                sinkType,
                STATUS_PENDING,
                null,
                null
        );
    }

    ExternalAuditAnchor published(String reason, String manifestStatus) {
        return withPublicationStatus(STATUS_PUBLISHED, reason, manifestStatus);
    }

    ExternalAuditAnchor unverified(String reason) {
        return withPublicationStatus(STATUS_UNVERIFIED, reason, null);
    }

    ExternalAuditAnchor invalid(String reason) {
        return withPublicationStatus(STATUS_INVALID, reason, null);
    }

    ExternalAuditAnchor conflict(String reason) {
        return withPublicationStatus(STATUS_CONFLICT, reason, null);
    }

    @Deprecated
    ExternalAuditAnchor partial() {
        return unverified(REASON_HEAD_MANIFEST_UPDATE_FAILED);
    }

    @Deprecated
    ExternalAuditAnchor partial(String reason, String manifestStatus) {
        return withPublicationStatus(STATUS_UNVERIFIED, reason, manifestStatus);
    }

    private ExternalAuditAnchor withPublicationStatus(String status, String reason, String manifestStatus) {
        return new ExternalAuditAnchor(
                externalAnchorId,
                anchorIdVersion,
                localAnchorId,
                partitionKey,
                chainPosition,
                lastEventHash,
                previousEventHash,
                hashAlgorithm,
                schemaVersion,
                createdAt,
                sinkType,
                status,
                reason,
                manifestStatus
        );
    }

    static String deterministicAnchorId(String source, String schemaVersion, String partitionKey, long chainPosition, String eventHash) {
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
