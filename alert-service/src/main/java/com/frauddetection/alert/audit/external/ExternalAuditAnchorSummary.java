package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.AuditAnchorDocument;

import java.time.Instant;

public record ExternalAuditAnchorSummary(
        @JsonProperty("anchor_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String anchorId,

        @JsonProperty("external_anchor_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String externalAnchorId,

        @JsonProperty("local_anchor_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String localAnchorId,

        @JsonProperty("chain_position")
        long chainPosition,

        @JsonProperty("last_event_hash")
        String lastEventHash,

        @JsonProperty("hash_algorithm")
        String hashAlgorithm,

        @JsonProperty("schema_version")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String schemaVersion,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("sink_type")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String sinkType,

        @JsonProperty("publication_status")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String publicationStatus
) {
    static ExternalAuditAnchorSummary fromLocal(AuditAnchorDocument document) {
        return new ExternalAuditAnchorSummary(
                document.anchorId(),
                null,
                null,
                document.chainPosition(),
                document.lastEventHash(),
                document.hashAlgorithm(),
                null,
                document.createdAt(),
                null,
                null
        );
    }

    static ExternalAuditAnchorSummary fromExternal(ExternalAuditAnchor anchor) {
        return new ExternalAuditAnchorSummary(
                null,
                anchor.externalAnchorId(),
                anchor.localAnchorId(),
                anchor.chainPosition(),
                anchor.lastEventHash(),
                anchor.hashAlgorithm(),
                anchor.schemaVersion(),
                anchor.createdAt(),
                anchor.sinkType(),
                anchor.publicationStatus()
        );
    }
}
