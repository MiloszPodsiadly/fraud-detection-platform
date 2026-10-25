package com.frauddetection.alert.audit.outbox;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WriteActionAuditOutboxClaimStore {

    private final MongoTemplate mongoTemplate;

    public WriteActionAuditOutboxClaimStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate is required");
    }

    public Optional<WriteActionAuditOutboxRecord> claimForPublishing(
            String outboxId,
            Instant now,
            String claimOwner,
            Duration claimLeaseDuration
    ) {
        if (outboxId == null || outboxId.isBlank()) {
            return Optional.empty();
        }
        Instant claimExpiresAt = now.plus(Objects.requireNonNull(claimLeaseDuration, "claimLeaseDuration is required"));
        String claimToken = UUID.randomUUID().toString();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").is(outboxId),
                new Criteria().orOperator(
                        new Criteria().andOperator(
                                Criteria.where("status").in(List.of(
                                        WriteActionAuditOutboxStatus.PENDING,
                                        WriteActionAuditOutboxStatus.FAILED_RETRYABLE
                                )),
                                new Criteria().orOperator(
                                        Criteria.where("next_attempt_at").is(null),
                                        Criteria.where("next_attempt_at").lte(now)
                                )
                        ),
                        new Criteria().andOperator(
                                Criteria.where("status").is(WriteActionAuditOutboxStatus.PUBLISHING),
                                Criteria.where("claim_expires_at").lte(now)
                        )
                )
        ));
        Update update = new Update()
                .set("status", WriteActionAuditOutboxStatus.PUBLISHING)
                .set("last_attempt_at", now)
                .set("claimed_at", now)
                .set("claim_owner", claimOwner)
                .set("claim_token", claimToken)
                .set("claim_expires_at", claimExpiresAt)
                .inc("claim_generation", 1L);
        WriteActionAuditOutboxRecord claimed = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                WriteActionAuditOutboxRecord.class
        );
        return Optional.ofNullable(claimed);
    }

    public boolean markPublished(WriteActionAuditOutboxRecord claim, Instant now) {
        if (!hasFence(claim)) return false;
        Update update = new Update()
                .set("status", WriteActionAuditOutboxStatus.PUBLISHED)
                .set("published_at", now)
                .set("last_attempt_at", now)
                .unset("next_attempt_at")
                .unset("claimed_at")
                .unset("claim_owner")
                .unset("claim_token")
                .unset("claim_expires_at")
                .unset("last_error_code")
                .unset("last_error_message");
        return mongoTemplate.updateFirst(ownedClaim(claim, now), update, WriteActionAuditOutboxRecord.class)
                .getModifiedCount() == 1;
    }

    public boolean markFailed(WriteActionAuditOutboxRecord claim, Instant now, String errorCode) {
        if (!hasFence(claim)) return false;
        int nextAttemptCount = claim.getAttemptCount() + 1;
        boolean permanent = errorCode != null && (errorCode.startsWith("INVALID_")
                || errorCode.startsWith("IMMUTABLE_"))
                || nextAttemptCount >= Math.max(1, claim.getMaxAttempts()) + Math.max(0, claim.getRecoveryCount());
        Update update = new Update()
                .set("status", permanent ? WriteActionAuditOutboxStatus.FAILED_PERMANENT
                        : WriteActionAuditOutboxStatus.FAILED_RETRYABLE)
                .set("attempt_count", nextAttemptCount)
                .set("last_attempt_at", now)
                .set("last_error_code", errorCode)
                .set("last_error_message", "Audit publication failed")
                .unset("claimed_at")
                .unset("claim_owner")
                .unset("claim_token")
                .unset("claim_expires_at");
        if (permanent) update.unset("next_attempt_at");
        else update.set("next_attempt_at", now.plus(Duration.ofMinutes(5L * nextAttemptCount)));
        return mongoTemplate.updateFirst(ownedClaim(claim, now), update, WriteActionAuditOutboxRecord.class)
                .getModifiedCount() == 1;
    }

    private Query ownedClaim(WriteActionAuditOutboxRecord claim, Instant now) {
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(claim.getOutboxId()),
                Criteria.where("status").is(WriteActionAuditOutboxStatus.PUBLISHING),
                Criteria.where("claim_owner").is(claim.getClaimOwner()),
                Criteria.where("claim_token").is(claim.getClaimToken()),
                Criteria.where("claim_generation").is(claim.getClaimGeneration()),
                Criteria.where("claimed_at").is(claim.getClaimedAt()),
                Criteria.where("claim_expires_at").is(claim.getClaimExpiresAt()).gt(now),
                Criteria.where("attempt_count").is(claim.getAttemptCount()),
                Criteria.where("max_attempts").is(claim.getMaxAttempts())
        ));
    }

    private boolean hasFence(WriteActionAuditOutboxRecord claim) {
        return claim != null && claim.getOutboxId() != null && claim.getClaimOwner() != null
                && claim.getClaimToken() != null && claim.getClaimedAt() != null
                && claim.getClaimExpiresAt() != null && claim.getClaimGeneration() > 0;
    }
}
