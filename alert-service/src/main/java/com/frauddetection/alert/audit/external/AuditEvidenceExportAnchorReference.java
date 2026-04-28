package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.AuditAnchorDocument;

public record AuditEvidenceExportAnchorReference(
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

        @JsonProperty("sink_type")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String sinkType,

        @JsonProperty("publication_status")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String publicationStatus,

        @JsonProperty("publication_reason")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String publicationReason,

        @JsonProperty("manifest_status")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String manifestStatus
) {
    static AuditEvidenceExportAnchorReference local(AuditAnchorDocument document) {
        return new AuditEvidenceExportAnchorReference(
                document.anchorId(),
                null,
                null,
                document.chainPosition(),
                document.lastEventHash(),
                document.hashAlgorithm(),
                null,
                null,
                null,
                null,
                null
        );
    }

    static AuditEvidenceExportAnchorReference external(ExternalAuditAnchor anchor) {
        return new AuditEvidenceExportAnchorReference(
                null,
                anchor.externalAnchorId(),
                anchor.localAnchorId(),
                anchor.chainPosition(),
                anchor.lastEventHash(),
                anchor.hashAlgorithm(),
                anchor.schemaVersion(),
                anchor.sinkType(),
                anchor.publicationStatus(),
                anchor.publicationReason(),
                anchor.manifestStatus()
        );
    }
}
