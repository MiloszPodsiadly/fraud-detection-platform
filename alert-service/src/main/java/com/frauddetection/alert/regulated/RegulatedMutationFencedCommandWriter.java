package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

@Service
public class RegulatedMutationFencedCommandWriter {

    private final MongoTemplate mongoTemplate;
    private final AlertServiceMetrics metrics;

    public RegulatedMutationFencedCommandWriter(MongoTemplate mongoTemplate, AlertServiceMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.metrics = metrics;
    }

    public void transition(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationState expectedState,
            RegulatedMutationExecutionStatus expectedExecutionStatus,
            RegulatedMutationState newState,
            RegulatedMutationExecutionStatus newExecutionStatus,
            String lastError,
            Consumer<Update> allowedFieldUpdates
    ) {
        if (claimToken == null) {
            throw new IllegalArgumentException("Regulated mutation fenced transition requires claim token.");
        }
        Instant now = Instant.now();
        Instant startedAt = now;
        Query query = activeLeaseQuery(claimToken, expectedState, expectedExecutionStatus, now);
        Update update = new Update()
                .set("state", newState)
                .set("execution_status", newExecutionStatus)
                .set("updated_at", now)
                .set("last_heartbeat_at", now)
                .set("last_error", lastError);
        if (allowedFieldUpdates != null) {
            allowedFieldUpdates.accept(update);
        }

        UpdateResult result = mongoTemplate.updateFirst(query, update, RegulatedMutationCommandDocument.class);
        if (result.getMatchedCount() == 0) {
            StaleRegulatedMutationLeaseReason reason = classifyRejection(
                    claimToken,
                    expectedState,
                    expectedExecutionStatus,
                    now
            );
            metrics.recordRegulatedMutationFencedTransition(
                    claimToken.mutationModelVersion(),
                    expectedState,
                    newState,
                    "REJECTED",
                    reason.name()
            );
            metrics.recordRegulatedMutationStaleWriteRejected(
                    claimToken.mutationModelVersion(),
                    expectedState,
                    reason.name()
            );
            metrics.recordRegulatedMutationLeaseRemainingAtTransition(
                    claimToken.mutationModelVersion(),
                    expectedState,
                    "REJECTED",
                    Duration.between(now, claimToken.leaseExpiresAt())
            );
            metrics.recordRegulatedMutationTransitionLatency(
                    claimToken.mutationModelVersion(),
                    expectedState,
                    "REJECTED",
                    Duration.between(startedAt, Instant.now())
            );
            throw new StaleRegulatedMutationLeaseException(claimToken.commandId(), reason);
        }
        metrics.recordRegulatedMutationFencedTransition(
                claimToken.mutationModelVersion(),
                expectedState,
                newState,
                "SUCCESS",
                "NONE"
        );
        metrics.recordRegulatedMutationLeaseRemainingAtTransition(
                claimToken.mutationModelVersion(),
                expectedState,
                "SUCCESS",
                Duration.between(now, claimToken.leaseExpiresAt())
        );
        metrics.recordRegulatedMutationTransitionLatency(
                claimToken.mutationModelVersion(),
                expectedState,
                "SUCCESS",
                Duration.between(startedAt, Instant.now())
        );
    }

    public void validateActiveLease(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationState expectedState,
            RegulatedMutationExecutionStatus expectedExecutionStatus
    ) {
        if (claimToken == null) {
            throw new IllegalArgumentException("Regulated mutation lease validation requires claim token.");
        }
        Instant now = Instant.now();
        long matches = mongoTemplate.count(
                activeLeaseQuery(claimToken, expectedState, expectedExecutionStatus, now),
                RegulatedMutationCommandDocument.class
        );
        if (matches == 0) {
            StaleRegulatedMutationLeaseReason reason = classifyRejection(
                    claimToken,
                    expectedState,
                    expectedExecutionStatus,
                    now
            );
            metrics.recordRegulatedMutationStaleWriteRejected(
                    claimToken.mutationModelVersion(),
                    expectedState,
                    reason.name()
            );
            throw new StaleRegulatedMutationLeaseException(claimToken.commandId(), reason);
        }
        metrics.recordRegulatedMutationLeaseRemainingAtTransition(
                claimToken.mutationModelVersion(),
                expectedState,
                "SUCCESS",
                Duration.between(now, claimToken.leaseExpiresAt())
        );
    }

    public void recoveryTransition(
            RegulatedMutationCommandDocument document,
            RegulatedMutationState newState,
            RegulatedMutationExecutionStatus newExecutionStatus,
            String lastError,
            Consumer<Update> allowedFieldUpdates
    ) {
        if (document == null || document.getId() == null || document.getId().isBlank()) {
            throw new IllegalArgumentException("Regulated mutation recovery transition requires persisted command document.");
        }
        Instant now = Instant.now();
        Query query = recoveryQuery(document, now);
        Update update = new Update()
                .set("state", newState)
                .set("execution_status", newExecutionStatus)
                .set("updated_at", now)
                .set("last_heartbeat_at", now)
                .set("last_error", lastError);
        if (allowedFieldUpdates != null) {
            allowedFieldUpdates.accept(update);
        }

        UpdateResult result = mongoTemplate.updateFirst(query, update, RegulatedMutationCommandDocument.class);
        if (result.getMatchedCount() == 0) {
            metrics.recordRegulatedMutationStaleWriteRejected(
                    document.mutationModelVersionOrLegacy(),
                    document.getState(),
                    StaleRegulatedMutationLeaseReason.RECOVERY_WRITE_CONFLICT.name()
            );
            throw new RegulatedMutationRecoveryWriteConflictException(document.getId());
        }
    }

    private Query activeLeaseQuery(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationState expectedState,
            RegulatedMutationExecutionStatus expectedExecutionStatus,
            Instant now
    ) {
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(claimToken.commandId()),
                Criteria.where("lease_owner").is(claimToken.leaseOwner()),
                Criteria.where("lease_expires_at").gt(now),
                Criteria.where("state").is(expectedState),
                Criteria.where("execution_status").is(expectedExecutionStatus),
                mutationModelCriteria(claimToken.mutationModelVersion())
        ));
    }

    private Query recoveryQuery(RegulatedMutationCommandDocument document, Instant now) {
        Criteria nonClaimedRecoveryCondition = new Criteria().orOperator(
                Criteria.where("execution_status").ne(RegulatedMutationExecutionStatus.PROCESSING),
                Criteria.where("lease_expires_at").lte(now),
                Criteria.where("lease_expires_at").exists(false),
                Criteria.where("lease_expires_at").is(null)
        );
        Criteria[] criteria = document.getPublicStatus() == null
                ? new Criteria[]{
                Criteria.where("_id").is(document.getId()),
                Criteria.where("state").is(document.getState()),
                Criteria.where("execution_status").is(document.getExecutionStatus()),
                mutationModelCriteria(document.mutationModelVersionOrLegacy()),
                nonClaimedRecoveryCondition
        }
                : new Criteria[]{
                Criteria.where("_id").is(document.getId()),
                Criteria.where("state").is(document.getState()),
                Criteria.where("execution_status").is(document.getExecutionStatus()),
                Criteria.where("public_status").is(document.getPublicStatus()),
                mutationModelCriteria(document.mutationModelVersionOrLegacy()),
                nonClaimedRecoveryCondition
        };
        return new Query(new Criteria().andOperator(criteria));
    }

    private Criteria mutationModelCriteria(RegulatedMutationModelVersion modelVersion) {
        if (modelVersion == RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION) {
            return new Criteria().orOperator(
                    Criteria.where("mutation_model_version").is(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                    Criteria.where("mutation_model_version").exists(false),
                    Criteria.where("mutation_model_version").is(null)
            );
        }
        return Criteria.where("mutation_model_version").is(modelVersion);
    }

    private StaleRegulatedMutationLeaseReason classifyRejection(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationState expectedState,
            RegulatedMutationExecutionStatus expectedExecutionStatus,
            Instant now
    ) {
        RegulatedMutationCommandDocument current = mongoTemplate.findById(
                claimToken.commandId(),
                RegulatedMutationCommandDocument.class
        );
        if (current == null) {
            return StaleRegulatedMutationLeaseReason.COMMAND_NOT_FOUND;
        }
        if (!claimToken.leaseOwner().equals(current.getLeaseOwner())) {
            return StaleRegulatedMutationLeaseReason.STALE_LEASE_OWNER;
        }
        if (current.getLeaseExpiresAt() == null || !current.getLeaseExpiresAt().isAfter(now)) {
            return StaleRegulatedMutationLeaseReason.EXPIRED_LEASE;
        }
        if (current.getState() != expectedState) {
            return StaleRegulatedMutationLeaseReason.EXPECTED_STATE_MISMATCH;
        }
        if (current.getExecutionStatus() != expectedExecutionStatus) {
            return StaleRegulatedMutationLeaseReason.EXPECTED_STATUS_MISMATCH;
        }
        return StaleRegulatedMutationLeaseReason.UNKNOWN;
    }
}
