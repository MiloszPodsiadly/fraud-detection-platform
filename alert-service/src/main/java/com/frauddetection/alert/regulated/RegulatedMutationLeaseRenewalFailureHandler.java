package com.frauddetection.alert.regulated;

import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RegulatedMutationLeaseRenewalFailureHandler {

    static final String BUDGET_EXCEEDED_REASON = "LEASE_RENEWAL_BUDGET_EXCEEDED";

    private final MongoTemplate mongoTemplate;
    private final RegulatedMutationLeaseRenewalPolicy policy;
    private final RegulatedMutationPublicStatusMapper publicStatusMapper;

    public RegulatedMutationLeaseRenewalFailureHandler(
            MongoTemplate mongoTemplate,
            RegulatedMutationLeaseRenewalPolicy policy,
            RegulatedMutationPublicStatusMapper publicStatusMapper
    ) {
        this.mongoTemplate = mongoTemplate;
        this.policy = policy;
        this.publicStatusMapper = publicStatusMapper;
    }

    void markBudgetExceededRecovery(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument current,
            Instant now
    ) {
        if (claimToken == null || current == null || now == null) {
            throw new RegulatedMutationLeaseRenewalException(
                    claimToken == null ? null : claimToken.commandId(),
                    RegulatedMutationLeaseRenewalReason.BUDGET_EXCEEDED
            );
        }
        RegulatedMutationModelVersion modelVersion = current.mutationModelVersionOrLegacy();
        RegulatedMutationState recoveryState = policy.recoveryStateForBudgetExceeded(modelVersion, current.getState());
        Update update = new Update()
                .set("execution_status", RegulatedMutationExecutionStatus.RECOVERY_REQUIRED)
                .set("degradation_reason", BUDGET_EXCEEDED_REASON)
                .set("last_error", BUDGET_EXCEEDED_REASON)
                .set("updated_at", now)
                .set("last_heartbeat_at", now);
        if (recoveryState != current.getState()) {
            update.set("state", recoveryState)
                    .set("public_status", publicStatusMapper.submitDecisionStatus(recoveryState, modelVersion));
        }

        UpdateResult result = mongoTemplate.updateFirst(
                budgetExceededRecoveryQuery(claimToken, current, now),
                update,
                RegulatedMutationCommandDocument.class
        );
        if (result.getMatchedCount() == 0) {
            throw new RegulatedMutationLeaseRenewalException(
                    claimToken.commandId(),
                    RegulatedMutationLeaseRenewalReason.BUDGET_EXCEEDED
            );
        }
    }

    private Query budgetExceededRecoveryQuery(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument current,
            Instant now
    ) {
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(claimToken.commandId()),
                Criteria.where("lease_owner").is(claimToken.leaseOwner()),
                Criteria.where("lease_expires_at").gt(now),
                Criteria.where("execution_status").is(RegulatedMutationExecutionStatus.PROCESSING),
                Criteria.where("state").is(current.getState()),
                mutationModelCriteria(claimToken.mutationModelVersion()),
                renewableStateCriteria(current.mutationModelVersionOrLegacy(), current.getState())
        ));
    }

    private Criteria renewableStateCriteria(RegulatedMutationModelVersion modelVersion, RegulatedMutationState state) {
        if (!policy.isRenewable(modelVersion, state, RegulatedMutationExecutionStatus.PROCESSING)) {
            return Criteria.where("state").is("__NON_RENEWABLE__");
        }
        return Criteria.where("state").is(state);
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
}
