package com.frauddetection.alert.regulated;

import java.time.Duration;
import java.time.Instant;

public record RegulatedMutationLeaseRenewalDecision(
        RegulatedMutationLeaseRenewalDecisionType type,
        RegulatedMutationLeaseRenewalReason reason,
        Instant newLeaseExpiresAt,
        Duration grantedLeaseDuration,
        Duration budgetRemainingAfterRenewal,
        boolean cappedBySingleExtension,
        boolean cappedByTotalBudget,
        Long resultingRevision
) {

    public static RegulatedMutationLeaseRenewalDecision renew(
            Instant newLeaseExpiresAt,
            Duration grantedLeaseDuration,
            Duration budgetRemainingAfterRenewal,
            boolean cappedBySingleExtension,
            boolean cappedByTotalBudget
    ) {
        return new RegulatedMutationLeaseRenewalDecision(
                RegulatedMutationLeaseRenewalDecisionType.RENEW,
                RegulatedMutationLeaseRenewalReason.NONE,
                newLeaseExpiresAt,
                grantedLeaseDuration,
                budgetRemainingAfterRenewal,
                cappedBySingleExtension,
                cappedByTotalBudget,
                null
        );
    }

    public static RegulatedMutationLeaseRenewalDecision rejected(RegulatedMutationLeaseRenewalReason reason) {
        return new RegulatedMutationLeaseRenewalDecision(
                RegulatedMutationLeaseRenewalDecisionType.REJECTED,
                reason == null ? RegulatedMutationLeaseRenewalReason.UNKNOWN : reason,
                null,
                Duration.ZERO,
                Duration.ZERO,
                false,
                false,
                null
        );
    }

    public static RegulatedMutationLeaseRenewalDecision budgetExceeded() {
        return new RegulatedMutationLeaseRenewalDecision(
                RegulatedMutationLeaseRenewalDecisionType.BUDGET_EXCEEDED,
                RegulatedMutationLeaseRenewalReason.BUDGET_EXCEEDED,
                null,
                Duration.ZERO,
                Duration.ZERO,
                false,
                false,
                null
        );
    }

    public RegulatedMutationLeaseRenewalDecision withResultingRevision(long revision) {
        if (type != RegulatedMutationLeaseRenewalDecisionType.RENEW || revision < 0) {
            throw new IllegalStateException("Only a successful lease renewal can expose a resulting revision.");
        }
        return new RegulatedMutationLeaseRenewalDecision(
                type,
                reason,
                newLeaseExpiresAt,
                grantedLeaseDuration,
                budgetRemainingAfterRenewal,
                cappedBySingleExtension,
                cappedByTotalBudget,
                revision
        );
    }
}
