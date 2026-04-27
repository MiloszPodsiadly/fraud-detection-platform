package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.AuditAnchorDocument;

import java.time.Instant;
import java.util.UUID;

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

        @JsonProperty("hash_algorithm")
        String hashAlgorithm,

        @JsonProperty("schema_version")
        String schemaVersion,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("sink_type")
        String sinkType,

        @JsonProperty("publication_status")
        String publicationStatus
) {
    public static final String SCHEMA_VERSION = "1.0";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    public static ExternalAuditAnchor from(AuditAnchorDocument localAnchor, String sinkType) {
        return new ExternalAuditAnchor(
                UUID.randomUUID().toString(),
                localAnchor.anchorId(),
                localAnchor.partitionKey(),
                localAnchor.chainPosition(),
                localAnchor.lastEventHash(),
                localAnchor.hashAlgorithm(),
                SCHEMA_VERSION,
                Instant.now(),
                sinkType,
                STATUS_PUBLISHED
        );
    }
}
