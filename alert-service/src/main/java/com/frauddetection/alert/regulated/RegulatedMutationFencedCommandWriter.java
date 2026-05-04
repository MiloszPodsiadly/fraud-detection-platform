package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

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
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").is(claimToken.commandId()),
                Criteria.where("lease_owner").is(claimToken.leaseOwner()),
                Criteria.where("lease_expires_at").gt(now),
                Criteria.where("state").is(expectedState),
                Criteria.where("execution_status").is(expectedExecutionStatus)
        ));
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
            throw new StaleRegulatedMutationLeaseException(claimToken.commandId(), reason);
        }
        metrics.recordRegulatedMutationFencedTransition(
                claimToken.mutationModelVersion(),
                expectedState,
                newState,
                "SUCCESS",
                "NONE"
        );
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
