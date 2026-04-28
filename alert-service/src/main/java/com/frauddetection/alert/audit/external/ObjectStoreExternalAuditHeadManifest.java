package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

record ObjectStoreExternalAuditHeadManifest(
        @JsonProperty("partition_key")
        String partitionKey,

        @JsonProperty("latest_chain_position")
        long latestChainPosition,

        @JsonProperty("latest_anchor_id")
        String latestAnchorId,

        @JsonProperty("latest_external_key")
        String latestExternalKey,

        @JsonProperty("latest_event_hash")
        String latestEventHash,

        @JsonProperty("updated_at")
        Instant updatedAt,

        @JsonProperty("manifest_hash")
        String manifestHash
) {
    static ObjectStoreExternalAuditHeadManifest unsigned(ExternalAuditAnchor anchor, String externalKey, Instant updatedAt) {
        return new ObjectStoreExternalAuditHeadManifest(
                anchor.partitionKey(),
                anchor.chainPosition(),
                anchor.localAnchorId(),
                externalKey,
                anchor.lastEventHash(),
                updatedAt,
                null
        );
    }

    ObjectStoreExternalAuditHeadManifest withManifestHash(String hash) {
        return new ObjectStoreExternalAuditHeadManifest(
                partitionKey,
                latestChainPosition,
                latestAnchorId,
                latestExternalKey,
                latestEventHash,
                updatedAt,
                hash
        );
    }

    ObjectStoreExternalAuditHeadManifest withoutManifestHash() {
        return withManifestHash(null);
    }
}
