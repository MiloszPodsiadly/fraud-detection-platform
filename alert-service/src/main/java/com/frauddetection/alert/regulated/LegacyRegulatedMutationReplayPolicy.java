package com.frauddetection.alert.regulated;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class LegacyRegulatedMutationReplayPolicy implements RegulatedMutationReplayPolicy {

    private final RegulatedMutationLeasePolicy leasePolicy;

    public LegacyRegulatedMutationReplayPolicy(RegulatedMutationLeasePolicy leasePolicy) {
        this.leasePolicy = leasePolicy;
    }

    @Override
    public RegulatedMutationModelVersion modelVersion() {
        return RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION;
    }

    @Override
    public RegulatedMutationReplayDecision resolve(RegulatedMutationCommandDocument document, Instant now) {
        if (leasePolicy.isActiveProcessingLease(document, now)) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS,
                    RegulatedMutationState.REQUESTED,
                    null
            );
        }
        if (document.getExecutionStatus() == RegulatedMutationExecutionStatus.RECOVERY_REQUIRED) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE,
                    legacyRecoveryState(document),
                    "RECOVERY_REQUIRED"
            );
        }
        if (document.getResponseSnapshot() != null && !needsSuccessAuditRetry(document)) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT,
                    document.getState(),
                    null
            );
        }
        if (requiresRecoveryWithoutSnapshot(document)) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE,
                    legacyRecoveryState(document),
                    "RECOVERY_REQUIRED"
            );
        }
        return RegulatedMutationReplayDecision.none();
    }

    public static boolean needsSuccessAuditRetry(RegulatedMutationCommandDocument document) {
        return document.getState() == RegulatedMutationState.SUCCESS_AUDIT_PENDING
                && !document.isSuccessAuditRecorded();
    }

    private static boolean requiresRecoveryWithoutSnapshot(RegulatedMutationCommandDocument document) {
        return switch (document.getState()) {
            case BUSINESS_COMMITTING, BUSINESS_COMMITTED, SUCCESS_AUDIT_PENDING, COMMITTED_DEGRADED, EVIDENCE_PENDING,
                 EVIDENCE_CONFIRMED, COMMITTED, FINALIZED_VISIBLE, FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                 FINALIZED_EVIDENCE_CONFIRMED -> document.getResponseSnapshot() == null;
            default -> false;
        };
    }

    public static RegulatedMutationState legacyRecoveryState(RegulatedMutationCommandDocument document) {
        if (document.getState() == RegulatedMutationState.BUSINESS_COMMITTING) {
            return RegulatedMutationState.BUSINESS_COMMITTING;
        }
        return RegulatedMutationState.FAILED;
    }
}
