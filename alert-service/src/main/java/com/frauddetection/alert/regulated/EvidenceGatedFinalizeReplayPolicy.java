package com.frauddetection.alert.regulated;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class EvidenceGatedFinalizeReplayPolicy implements RegulatedMutationReplayPolicy {

    private final RegulatedMutationLeasePolicy leasePolicy;

    public EvidenceGatedFinalizeReplayPolicy(RegulatedMutationLeasePolicy leasePolicy) {
        this.leasePolicy = leasePolicy;
    }

    @Override
    public RegulatedMutationModelVersion modelVersion() {
        return RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1;
    }

    @Override
    public RegulatedMutationReplayDecision resolve(RegulatedMutationCommandDocument document, Instant now) {
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
        if (document.getState() == RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE
                || document.getState() == RegulatedMutationState.FAILED_BUSINESS_VALIDATION) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.REJECTED_RESPONSE,
                    document.getState(),
                    null
            );
        }
        if (document.getResponseSnapshot() != null) {
            return RegulatedMutationReplayDecision.of(
                    RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT,
                    document.getState(),
                    null
            );
        }
        return RegulatedMutationReplayDecision.none();
    }
}
