package com.frauddetection.alert.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "audit_chain_anchors")
@Immutable
@CompoundIndex(name = "audit_anchor_partition_created_at_idx", def = "{'partition_key': 1, 'created_at': -1}")
public record AuditAnchorDocument(
        @Id
        @Field("anchor_id")
        String anchorId,

        @Field("created_at")
        Instant createdAt,

        @Field("partition_key")
        String partitionKey,

        @Field("last_event_hash")
        String lastEventHash,

        @Field("chain_position")
        long chainPosition,

        @Field("hash_algorithm")
        String hashAlgorithm
) {
    static AuditAnchorDocument from(String anchorId, AuditEventDocument event) {
        return new AuditAnchorDocument(
                anchorId,
                Instant.now(),
                event.partitionKey(),
                event.eventHash(),
                event.chainPosition(),
                event.hashAlgorithm()
        );
    }
}
