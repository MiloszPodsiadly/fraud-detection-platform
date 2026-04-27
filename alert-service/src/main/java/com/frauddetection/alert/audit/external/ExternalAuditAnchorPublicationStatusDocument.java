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

        @Field("external_published_at")
        Instant externalPublishedAt,

        @Field("external_sink_type")
        String externalSinkType,

        @Field("external_publish_attempts")
        int externalPublishAttempts,

        @Field("last_external_publish_failure_reason")
        String lastExternalPublishFailureReason,

        @Field("updated_at")
        Instant updatedAt
) {
}
