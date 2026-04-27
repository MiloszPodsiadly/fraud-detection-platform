package com.frauddetection.alert.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "audit_chain_locks")
public record AuditChainLockDocument(
        @Id
        @Field("partition_key")
        String partitionKey,

        @Field("owner_token")
        String ownerToken,

        @Field("locked_until")
        Instant lockedUntil
) {
}
