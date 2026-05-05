package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RegulatedMutationLeaseRenewalPolicy {

    private static final Set<RegulatedMutationState> RECOVERY_STATES = Set.of(
            RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
            RegulatedMutationState.FAILED
    );
    private static final Set<RegulatedMutationState> TERMINAL_STATES = Set.of(
            RegulatedMutationState.FINALIZED_VISIBLE,
            RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
            RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED,
            RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE,
            RegulatedMutationState.FAILED_BUSINESS_VALIDATION,
            RegulatedMutationState.SUCCESS_AUDIT_RECORDED,
            RegulatedMutationState.EVIDENCE_PENDING,
            RegulatedMutationState.EVIDENCE_CONFIRMED,
            RegulatedMutationState.COMMITTED,
            RegulatedMutationState.COMMITTED_DEGRADED,
            RegulatedMutationState.REJECTED
    );

    private final Duration maxSingleExtension;
    private final Duration maxTotalLeaseDuration;
    private final int maxRenewalCount;
    private final Map<RegulatedMutationModelVersion, RegulatedMutationLeaseRenewalModelPolicy> modelPolicies;

    @Autowired
    RegulatedMutationLeaseRenewalPolicy(
            @Value("${app.regulated-mutations.lease-renewal.max-single-extension:PT30S}")
            Duration maxSingleExtension,
            @Value("${app.regulated-mutations.lease-renewal.max-total-lease-duration:PT2M}")
            Duration maxTotalLeaseDuration,
            @Value("${app.regulated-mutations.lease-renewal.max-renewal-count:3}")
            int maxRenewalCount,
            List<RegulatedMutationLeaseRenewalModelPolicy> modelPolicies
    ) {
        this.maxSingleExtension = positive(maxSingleExtension, "maxSingleExtension");
        this.maxTotalLeaseDuration = positive(maxTotalLeaseDuration, "maxTotalLeaseDuration");
        if (maxRenewalCount < 0) {
            throw new IllegalArgumentException("maxRenewalCount must not be negative.");
        }
        this.maxRenewalCount = maxRenewalCount;
        this.modelPolicies = modelPolicies(modelPolicies);
    }

    RegulatedMutationLeaseRenewalPolicy(
            Duration maxSingleExtension,
            Duration maxTotalLeaseDuration,
            int maxRenewalCount
    ) {
        this(maxSingleExtension, maxTotalLeaseDuration, maxRenewalCount, List.of(
                new LegacyLeaseRenewalModelPolicy(),
                new EvidenceGatedLeaseRenewalModelPolicy()
        ));
    }

    public RegulatedMutationLeaseRenewalDecision evaluate(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document,
            Duration requestedExtension,
            Instant now
    ) {
        if (claimToken == null) {
            throw new IllegalArgumentException("Regulated mutation lease renewal requires claim token.");
        }
        if (document == null) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.COMMAND_NOT_FOUND);
        }
        if (requestedExtension == null || !requestedExtension.isPositive()) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.INVALID_EXTENSION);
        }
        if (now == null) {
            throw new IllegalArgumentException("Regulated mutation lease renewal policy requires now.");
        }
        Instant effectiveNow = now;
        RegulatedMutationModelVersion modelVersion = document.mutationModelVersionOrLegacy();
        if (claimToken.mutationModelVersion() != modelVersion) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.MODEL_VERSION_MISMATCH);
        }
        if (!claimToken.leaseOwner().equals(document.getLeaseOwner())) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.STALE_OWNER);
        }
        if (document.getLeaseExpiresAt() == null || !document.getLeaseExpiresAt().isAfter(effectiveNow)) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.EXPIRED_LEASE);
        }
        if (isRecoveryState(document.getState()) || document.getExecutionStatus() == RegulatedMutationExecutionStatus.RECOVERY_REQUIRED) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.RECOVERY_STATE);
        }
        if (isTerminalState(document.getState())) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.TERMINAL_STATE);
        }
        if (document.getExecutionStatus() != RegulatedMutationExecutionStatus.PROCESSING) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.EXECUTION_STATUS_MISMATCH);
        }
        if (!isRenewable(modelVersion, document.getState(), document.getExecutionStatus())) {
            return RegulatedMutationLeaseRenewalDecision.rejected(RegulatedMutationLeaseRenewalReason.NON_RENEWABLE_STATE);
        }
        if (document.leaseRenewalCountOrZero() >= maxRenewalCount) {
            return RegulatedMutationLeaseRenewalDecision.budgetExceeded();
        }

        Instant budgetStartedAt = budgetStartedAt(claimToken, document, effectiveNow);
        Instant budgetEndsAt = budgetStartedAt.plus(maxTotalLeaseDuration);
        if (!budgetEndsAt.isAfter(effectiveNow)) {
            return RegulatedMutationLeaseRenewalDecision.budgetExceeded();
        }

        boolean cappedBySingleExtension = requestedExtension.compareTo(maxSingleExtension) > 0;
        Duration cappedExtension = cappedBySingleExtension ? maxSingleExtension : requestedExtension;
        Instant newLeaseExpiresAt = effectiveNow.plus(cappedExtension);
        boolean cappedByTotalBudget = newLeaseExpiresAt.isAfter(budgetEndsAt);
        if (cappedByTotalBudget) {
            newLeaseExpiresAt = budgetEndsAt;
        }
        if (!newLeaseExpiresAt.isAfter(document.getLeaseExpiresAt())) {
            return RegulatedMutationLeaseRenewalDecision.budgetExceeded();
        }
        return RegulatedMutationLeaseRenewalDecision.renew(
                newLeaseExpiresAt,
                Duration.between(effectiveNow, newLeaseExpiresAt),
                Duration.between(newLeaseExpiresAt, budgetEndsAt),
                cappedBySingleExtension,
                cappedByTotalBudget
        );
    }

    public boolean isRenewable(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus
    ) {
        if (executionStatus != RegulatedMutationExecutionStatus.PROCESSING || state == null) {
            return false;
        }
        RegulatedMutationModelVersion effectiveModel =
                modelVersion == null ? RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION : modelVersion;
        RegulatedMutationLeaseRenewalModelPolicy modelPolicy = modelPolicies.get(effectiveModel);
        return modelPolicy != null && modelPolicy.isRenewableState(state);
    }

    public RegulatedMutationState recoveryStateForBudgetExceeded(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState currentState
    ) {
        RegulatedMutationModelVersion effectiveModel =
                modelVersion == null ? RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION : modelVersion;
        RegulatedMutationLeaseRenewalModelPolicy modelPolicy = modelPolicies.get(effectiveModel);
        if (modelPolicy == null) {
            throw new IllegalArgumentException("No regulated mutation lease renewal model policy registered for "
                    + effectiveModel);
        }
        return modelPolicy.recoveryStateForBudgetExceeded(currentState);
    }

    public boolean isRecoveryState(RegulatedMutationState state) {
        return RECOVERY_STATES.contains(state);
    }

    public boolean isTerminalState(RegulatedMutationState state) {
        return TERMINAL_STATES.contains(state);
    }

    public Duration maxSingleExtension() {
        return maxSingleExtension;
    }

    public Duration maxTotalLeaseDuration() {
        return maxTotalLeaseDuration;
    }

    public int maxRenewalCount() {
        return maxRenewalCount;
    }

    Instant budgetStartedAt(
            RegulatedMutationClaimToken claimToken,
            RegulatedMutationCommandDocument document,
            Instant now
    ) {
        if (document.getLeaseBudgetStartedAt() != null) {
            return document.getLeaseBudgetStartedAt();
        }
        if (claimToken.claimedAt() != null) {
            return claimToken.claimedAt();
        }
        if (document.getCreatedAt() != null) {
            return document.getCreatedAt();
        }
        return now;
    }

    private Duration positive(Duration duration, String name) {
        if (duration == null || !duration.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return duration;
    }

    private Map<RegulatedMutationModelVersion, RegulatedMutationLeaseRenewalModelPolicy> modelPolicies(
            List<RegulatedMutationLeaseRenewalModelPolicy> policies
    ) {
        if (policies == null || policies.isEmpty()) {
            throw new IllegalArgumentException("Regulated mutation lease renewal model policies are required.");
        }
        Map<RegulatedMutationModelVersion, RegulatedMutationLeaseRenewalModelPolicy> byVersion =
                new EnumMap<>(RegulatedMutationModelVersion.class);
        for (RegulatedMutationLeaseRenewalModelPolicy policy : policies) {
            if (policy == null || policy.modelVersion() == null) {
                throw new IllegalArgumentException("Regulated mutation lease renewal model policy version is required.");
            }
            if (byVersion.putIfAbsent(policy.modelVersion(), policy) != null) {
                throw new IllegalArgumentException("Duplicate regulated mutation lease renewal model policy: "
                        + policy.modelVersion());
            }
        }
        for (RegulatedMutationModelVersion modelVersion : RegulatedMutationModelVersion.values()) {
            if (!byVersion.containsKey(modelVersion)) {
                throw new IllegalArgumentException("Missing regulated mutation lease renewal model policy: "
                        + modelVersion);
            }
        }
        return Map.copyOf(byVersion);
    }
}
