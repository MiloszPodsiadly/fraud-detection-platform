package com.frauddetection.alert.regulated;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class RegulatedMutationReplayResolver {

    private final RegulatedMutationLeasePolicy leasePolicy;

    public RegulatedMutationReplayResolver(RegulatedMutationLeasePolicy leasePolicy) {
        this.leasePolicy = leasePolicy;
    }

    public RegulatedMutationReplayDecision resolveLegacy(RegulatedMutationCommandDocument document, Instant now) {
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
        if (requiresLegacyRecoveryWithoutSnapshot(document)) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE,
                    legacyRecoveryState(document),
                    "RECOVERY_REQUIRED"
            );
        }
        return RegulatedMutationReplayDecision.none();
    }

    public RegulatedMutationReplayDecision resolveEvidenceGated(RegulatedMutationCommandDocument document, Instant now) {
        if (leasePolicy.isActiveProcessingLease(document, now)) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS,
                    document.getState(),
                    null
            );
        }
        if (document.getState() == RegulatedMutationState.FINALIZING) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.FINALIZING_REQUIRES_RECOVERY,
                    RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                    "FINALIZING_RETRY_REQUIRES_RECONCILIATION"
            );
        }
        if (document.getState() == RegulatedMutationState.FINALIZED_VISIBLE) {
            if (document.getResponseSnapshot() != null
                    && document.getLocalCommitMarker() != null
                    && document.isSuccessAuditRecorded()) {
                return RegulatedMutationReplayDecision.of(
                        RegulatedMutationReplayDecisionType.FINALIZED_VISIBLE_REPAIRABLE,
                        RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                        null
                );
            }
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.FINALIZED_VISIBLE_RECOVERY_REQUIRED,
                    RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                    "FINALIZED_VISIBLE_MISSING_PROOF"
            );
        }
        if (document.getExecutionStatus() == RegulatedMutationExecutionStatus.RECOVERY_REQUIRED
                || document.getState() == RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE,
                    RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                    "FINALIZE_RECOVERY_REQUIRED"
            );
        }
        if (document.getResponseSnapshot() != null) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT,
                    document.getState(),
                    null
            );
        }
        if (document.getState() == RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE
                || document.getState() == RegulatedMutationState.FAILED_BUSINESS_VALIDATION) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.REJECTED_RESPONSE,
                    document.getState(),
                    null
            );
        }
        return RegulatedMutationReplayDecision.none();
    }

    public boolean needsSuccessAuditRetry(RegulatedMutationCommandDocument document) {
        return document.getState() == RegulatedMutationState.SUCCESS_AUDIT_PENDING
                && !document.isSuccessAuditRecorded();
    }

    private boolean requiresLegacyRecoveryWithoutSnapshot(RegulatedMutationCommandDocument document) {
        return switch (document.getState()) {
            case BUSINESS_COMMITTING, BUSINESS_COMMITTED, SUCCESS_AUDIT_PENDING, COMMITTED_DEGRADED, EVIDENCE_PENDING,
                 EVIDENCE_CONFIRMED, COMMITTED, FINALIZED_VISIBLE, FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                 FINALIZED_EVIDENCE_CONFIRMED -> document.getResponseSnapshot() == null;
            default -> false;
        };
    }

    public RegulatedMutationState legacyRecoveryState(RegulatedMutationCommandDocument document) {
        if (document.getState() == RegulatedMutationState.BUSINESS_COMMITTING) {
            return RegulatedMutationState.BUSINESS_COMMITTING;
        }
        return RegulatedMutationState.FAILED;
    }
}
