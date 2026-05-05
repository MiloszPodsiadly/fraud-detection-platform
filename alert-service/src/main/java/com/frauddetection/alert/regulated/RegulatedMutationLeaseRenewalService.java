package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.mongodb.client.result.UpdateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class RegulatedMutationLeaseRenewalService {

    private final MongoTemplate mongoTemplate;
    private final RegulatedMutationLeaseRenewalPolicy policy;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public RegulatedMutationLeaseRenewalService(
            MongoTemplate mongoTemplate,
            RegulatedMutationLeaseRenewalPolicy policy,
            AlertServiceMetrics metrics
    ) {
        this(mongoTemplate, policy, metrics, Clock.systemUTC());
    }

    RegulatedMutationLeaseRenewalService(
            MongoTemplate mongoTemplate,
            RegulatedMutationLeaseRenewalPolicy policy,
            AlertServiceMetrics metrics,
            Clock clock
    ) {
        this.mongoTemplate = mongoTemplate;
        this.policy = policy;
        this.metrics = metrics;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public RegulatedMutationLeaseRenewalDecision renew(
            RegulatedMutationClaimToken claimToken,
            Duration requestedExtension,
            String context
    ) {
        if (claimToken == null) {
            throw new IllegalArgumentException("Regulated mutation lease renewal requires claim token.");
        }
        Instant now = clock.instant();
        RegulatedMutationCommandDocument current = mongoTemplate.findById(
                claimToken.commandId(),
                RegulatedMutationCommandDocument.class
        );
        RegulatedMutationLeaseRenewalDecision decision = policy.evaluate(
                claimToken,
                current,
                requestedExtension,
                now
        );
        if (decision.type() != RegulatedMutationLeaseRenewalDecisionType.RENEW) {
            recordRejection(claimToken, current, decision);
            throwException(claimToken, decision);
        }

        Instant budgetStartedAt = policy.budgetStartedAt(claimToken, current, now);
        Query query = renewalQuery(claimToken, current, now);
        Update update = new Update()
                .set("lease_expires_at", decision.newLeaseExpiresAt())
                .set("last_heartbeat_at", now)
                .set("last_lease_renewed_at", now)
                .set("lease_budget_started_at", budgetStartedAt)
                .set("updated_at", now)
                .inc("lease_renewal_count", 1);
        UpdateResult result = mongoTemplate.updateFirst(query, update, RegulatedMutationCommandDocument.class);
        if (result.getMatchedCount() == 0) {
            RegulatedMutationCommandDocument afterRace = mongoTemplate.findById(
                    claimToken.commandId(),
                    RegulatedMutationCommandDocument.class
            );
            RegulatedMutationLeaseRenewalDecision raceDecision = policy.evaluate(
                    claimToken,
                    afterRace,
                    requestedExtension,
                    now
            );
            recordRejection(claimToken, afterRace, raceDecision);
            throwException(claimToken, raceDecision);
        }

        recordSuccess(claimToken, current, decision);
        return decision;
    }

    private Query renewalQuery(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument current,
            Instant now
    ) {
        Criteria renewalCountWithinBudget = new Criteria().orOperator(
                Criteria.where("lease_renewal_count").exists(false),
                Criteria.where("lease_renewal_count").is(null),
                Criteria.where("lease_renewal_count").lt(policy.maxRenewalCount())
        );
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(claimToken.commandId()),
                Criteria.where("lease_owner").is(claimToken.leaseOwner()),
                Criteria.where("lease_expires_at").gt(now),
                Criteria.where("execution_status").is(RegulatedMutationExecutionStatus.PROCESSING),
                Criteria.where("state").is(current.getState()),
                mutationModelCriteria(claimToken.mutationModelVersion()),
                renewalCountWithinBudget
        ));
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

    private void recordSuccess(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument current,
            RegulatedMutationLeaseRenewalDecision decision
    ) {
        RegulatedMutationState state = current == null ? claimToken.expectedInitialState() : current.getState();
        metrics.recordRegulatedMutationLeaseRenewal(
                claimToken.mutationModelVersion(),
                state,
                "SUCCESS",
                "NONE"
        );
        metrics.recordRegulatedMutationLeaseRenewalBudgetRemaining(
                claimToken.mutationModelVersion(),
                state,
                decision.budgetRemainingAfterRenewal()
        );
        metrics.recordRegulatedMutationLeaseRenewalExtension(
                claimToken.mutationModelVersion(),
                state,
                "SUCCESS",
                decision.grantedLeaseDuration()
        );
        if (decision.cappedBySingleExtension()) {
            metrics.recordRegulatedMutationLeaseRenewalSingleExtensionCapped(
                    claimToken.mutationModelVersion(),
                    state
            );
        }
        if (decision.cappedByTotalBudget()) {
            metrics.recordRegulatedMutationLeaseRenewalTotalBudgetCapped(
                    claimToken.mutationModelVersion(),
                    state
            );
        }
    }

    private void recordRejection(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument current,
            RegulatedMutationLeaseRenewalDecision decision
    ) {
        RegulatedMutationState state = current == null ? claimToken.expectedInitialState() : current.getState();
        String outcome = decision.type() == RegulatedMutationLeaseRenewalDecisionType.BUDGET_EXCEEDED
                ? "BUDGET_EXCEEDED"
                : "REJECTED";
        metrics.recordRegulatedMutationLeaseRenewal(
                claimToken.mutationModelVersion(),
                state,
                outcome,
                decision.reason().name()
        );
        metrics.recordRegulatedMutationLeaseRenewalRejected(
                claimToken.mutationModelVersion(),
                state,
                decision.reason().name()
        );
        if (decision.type() == RegulatedMutationLeaseRenewalDecisionType.BUDGET_EXCEEDED) {
            metrics.recordRegulatedMutationLeaseRenewalBudgetExceeded(
                    claimToken.mutationModelVersion(),
                    state
            );
        }
    }

    private void throwException(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationLeaseRenewalDecision decision
    ) {
        if (decision.type() == RegulatedMutationLeaseRenewalDecisionType.BUDGET_EXCEEDED) {
            throw new RegulatedMutationLeaseRenewalBudgetExceededException(claimToken.commandId());
        }
        if (decision.reason() == RegulatedMutationLeaseRenewalReason.STALE_OWNER) {
            throw new StaleRegulatedMutationLeaseException(
                    claimToken.commandId(),
                    StaleRegulatedMutationLeaseReason.STALE_LEASE_OWNER
            );
        }
        if (decision.reason() == RegulatedMutationLeaseRenewalReason.EXPIRED_LEASE) {
            throw new StaleRegulatedMutationLeaseException(
                    claimToken.commandId(),
                    StaleRegulatedMutationLeaseReason.EXPIRED_LEASE
            );
        }
        throw new RegulatedMutationLeaseRenewalException(claimToken.commandId(), decision.reason());
    }
}
