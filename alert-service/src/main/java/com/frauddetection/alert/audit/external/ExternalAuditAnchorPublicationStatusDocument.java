package com.frauddetection.alert.audit.external;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "audit_external_anchor_publication_status")
@CompoundIndex(name = "external_anchor_publication_status_partition_position_idx", def = "{'partition_key': 1, 'chain_position': 1}", unique = true)
record ExternalAuditAnchorPublicationStatusDocument(
        @Id
        @Field("local_anchor_id")
        String localAnchorId,

        @Field("partition_key")
        String partitionKey,

        @Field("chain_position")
        long chainPosition,

        @Field("external_published")
        boolean externalPublished,

        @Field("external_publication_status")
        String externalPublicationStatus,

        @Field("external_object_status")
        String externalObjectStatus,

        @Field("head_manifest_status")
        String headManifestStatus,

        @Field("local_tracking_status")
        String localTrackingStatus,

        @Field("local_anchor_status")
        String localAnchorStatus,

        @Field("external_required")
        Boolean externalRequired,

        @Field("external_published_at")
        Instant externalPublishedAt,

        @Field("external_sink_type")
        String externalSinkType,

        @Field("external_key")
        String externalKey,

        @Field("anchor_hash")
        String anchorHash,

        @Field("external_hash")
        String externalHash,

        @Field("external_reference_verified_at")
        Instant externalReferenceVerifiedAt,

        @Field("external_immutability_level")
        String externalImmutabilityLevel,

        @Field("manifest_status")
        String manifestStatus,

        @Field("signature_status")
        String signatureStatus,

        @Field("signature")
        String signature,

        @Field("signing_key_id")
        String signingKeyId,

        @Field("signing_algorithm")
        String signingAlgorithm,

        @Field("signed_at")
        Instant signedAt,

        @Field("signing_authority")
        String signingAuthority,

        @Field("signed_payload_hash")
        String signedPayloadHash,

        @Field("external_publish_attempts")
        int externalPublishAttempts,

        @Field("last_external_publish_failure_reason")
        String lastExternalPublishFailureReason,

        @Field("updated_at")
        Instant updatedAt
) {
}
