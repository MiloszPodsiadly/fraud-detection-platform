package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

record ObjectStoreExternalAuditAnchorPayload(
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

        @JsonProperty("payload_hash")
        String payloadHash,

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
    static ObjectStoreExternalAuditAnchorPayload from(ExternalAuditAnchor anchor, String externalObjectKey, String payloadHash) {
        return new ObjectStoreExternalAuditAnchorPayload(
                anchor.localAnchorId(),
                anchor.partitionKey(),
                externalObjectKey,
                anchor.chainPosition(),
                anchor.lastEventHash(),
                payloadHash,
                anchor.hashAlgorithm(),
                anchor.schemaVersion(),
                anchor.createdAt(),
                anchor.sinkType(),
                anchor.publicationStatus()
        );
    }

    ExternalAuditAnchor toExternalAnchor() {
        return new ExternalAuditAnchor(
                "object-store:" + localAnchorId,
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
