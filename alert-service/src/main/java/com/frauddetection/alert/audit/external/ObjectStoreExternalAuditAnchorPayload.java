package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

record ObjectStoreExternalAuditAnchorPayload(
        @JsonProperty("anchor_id")
        String anchorId,

        @JsonProperty("source")
        String source,

        @JsonProperty("local_anchor_id")
        String localAnchorId,

        @JsonProperty("partition_key")
        String partitionKey,

        @JsonProperty("external_object_key")
        String externalObjectKey,

        @JsonProperty("chain_position")
        long chainPosition,

        @JsonProperty("event_hash")
        String eventHash,

        @JsonProperty("previous_event_hash")
        String previousEventHash,

        @JsonProperty("payload_hash")
        String payloadHash,

        @JsonProperty("hash_algorithm")
        String hashAlgorithm,

        @JsonProperty("schema_version")
        String schemaVersion,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("published_at_local")
        Instant publishedAtLocal,

        @JsonProperty("sink_type")
        String sinkType,

        @JsonProperty("publication_status")
        String publicationStatus,

        @JsonProperty("publication_reason")
        String publicationReason,

        @JsonProperty("manifest_status")
        String manifestStatus
) {
    static ObjectStoreExternalAuditAnchorPayload from(ExternalAuditAnchor anchor, String externalObjectKey, String payloadHash) {
        return new ObjectStoreExternalAuditAnchorPayload(
                anchor.externalAnchorId(),
                anchor.sinkType(),
                anchor.localAnchorId(),
                anchor.partitionKey(),
                externalObjectKey,
                anchor.chainPosition(),
                anchor.lastEventHash(),
                anchor.previousEventHash(),
                payloadHash,
                anchor.hashAlgorithm(),
                anchor.schemaVersion(),
                anchor.createdAt(),
                anchor.createdAt(),
                anchor.sinkType(),
                anchor.publicationStatus(),
                anchor.publicationReason(),
                anchor.manifestStatus()
        );
    }

    ExternalAuditAnchor toExternalAnchor() {
        return new ExternalAuditAnchor(
                anchorId,
                localAnchorId,
                partitionKey,
                chainPosition,
                eventHash,
                previousEventHash,
                hashAlgorithm,
                schemaVersion,
                publishedAtLocal == null ? createdAt : publishedAtLocal,
                sinkType,
                publicationStatus,
                publicationReason,
                manifestStatus
        );
    }
}
