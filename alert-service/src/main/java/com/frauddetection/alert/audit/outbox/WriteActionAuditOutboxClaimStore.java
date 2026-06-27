package com.frauddetection.alert.audit.outbox;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class WriteActionAuditOutboxClaimStore {

    private final MongoTemplate mongoTemplate;

    public WriteActionAuditOutboxClaimStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
    }

    public Optional<WriteActionAuditOutboxRecord> claimForPublishing(
            String outboxId,
            Instant now,
            String claimOwner
    ) {
        if (outboxId == null || outboxId.isBlank()) {
            return Optional.empty();
        }
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").is(outboxId),
                Criteria.where("status").in(List.of(
                        WriteActionAuditOutboxStatus.PENDING,
                        WriteActionAuditOutboxStatus.FAILED_RETRYABLE
                )),
                new Criteria().orOperator(
                        Criteria.where("next_attempt_at").is(null),
                        Criteria.where("next_attempt_at").lte(now)
                )
        ));
        Update update = new Update()
                .set("status", WriteActionAuditOutboxStatus.PUBLISHING)
                .set("last_attempt_at", now)
                .set("claimed_at", now)
                .set("claim_owner", claimOwner);
        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                WriteActionAuditOutboxRecord.class
        ));
    }
}
