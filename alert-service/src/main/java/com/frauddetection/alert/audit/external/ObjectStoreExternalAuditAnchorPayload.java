package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

record ObjectStoreExternalAuditAnchorPayload(
        @JsonProperty("external_anchor_id")
        String externalAnchorId,

        @JsonProperty("local_anchor_id")
        String localAnchorId,

        @JsonProperty("partition_key")
        String partitionKey,

        @JsonProperty("chain_position")
        long chainPosition,

        @JsonProperty("event_hash")
        String eventHash,

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
        String publicationStatus
) {
    static ObjectStoreExternalAuditAnchorPayload from(ExternalAuditAnchor anchor) {
        return new ObjectStoreExternalAuditAnchorPayload(
                anchor.externalAnchorId(),
                anchor.localAnchorId(),
                anchor.partitionKey(),
                anchor.chainPosition(),
                anchor.lastEventHash(),
                null,
                anchor.hashAlgorithm(),
                anchor.schemaVersion(),
                anchor.createdAt(),
                anchor.sinkType(),
                anchor.publicationStatus()
        );
    }

    ExternalAuditAnchor toExternalAnchor() {
        return new ExternalAuditAnchor(
                externalAnchorId,
                localAnchorId,
                partitionKey,
                chainPosition,
                eventHash,
                hashAlgorithm,
                schemaVersion,
                createdAt,
                sinkType,
                publicationStatus
        );
    }
}
