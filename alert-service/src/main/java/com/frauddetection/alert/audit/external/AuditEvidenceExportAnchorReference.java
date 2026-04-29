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

        @JsonProperty("partition_key")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String partitionKey,

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

        @JsonProperty("external_immutability_level")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExternalImmutabilityLevel externalImmutabilityLevel,

        @JsonProperty("external_key")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String externalKey,

        @JsonProperty("external_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String externalHash,

        @JsonProperty("publication_status")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String publicationStatus,

        @JsonProperty("publication_reason")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String publicationReason,

        @JsonProperty("manifest_status")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String manifestStatus,

        @JsonProperty("signature_status")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signatureStatus,

        @JsonProperty("signature")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signature,

        @JsonProperty("signing_key_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signingKeyId,

        @JsonProperty("signing_algorithm")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signingAlgorithm,

        @JsonProperty("signed_at")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        java.time.Instant signedAt,

        @JsonProperty("signing_authority")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signingAuthority,

        @JsonProperty("signed_payload_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String signedPayloadHash
) {
    static AuditEvidenceExportAnchorReference local(AuditAnchorDocument document) {
        return new AuditEvidenceExportAnchorReference(
                document.anchorId(),
                null,
                null,
                document.partitionKey(),
                document.chainPosition(),
                document.lastEventHash(),
                document.hashAlgorithm(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    static AuditEvidenceExportAnchorReference external(ExternalAuditAnchor anchor) {
        return external(anchor, null, ExternalImmutabilityLevel.NONE);
    }

    static AuditEvidenceExportAnchorReference external(
            ExternalAuditAnchor anchor,
            ExternalAnchorReference reference,
            ExternalImmutabilityLevel immutabilityLevel
    ) {
        return new AuditEvidenceExportAnchorReference(
                null,
                anchor.externalAnchorId(),
                anchor.localAnchorId(),
                anchor.partitionKey(),
                anchor.chainPosition(),
                anchor.lastEventHash(),
                anchor.hashAlgorithm(),
                anchor.schemaVersion(),
                anchor.sinkType(),
                immutabilityLevel == null ? ExternalImmutabilityLevel.NONE : immutabilityLevel,
                reference == null ? null : reference.externalKey(),
                reference == null ? null : reference.externalHash(),
                anchor.publicationStatus(),
                anchor.publicationReason(),
                anchor.manifestStatus(),
                reference == null ? null : reference.signatureStatus(),
                reference == null ? null : reference.signature(),
                reference == null ? null : reference.signingKeyId(),
                reference == null ? null : reference.signingAlgorithm(),
                reference == null ? null : reference.signedAt(),
                reference == null ? null : reference.signingAuthority(),
                reference == null ? null : reference.signedPayloadHash()
        );
    }
}
