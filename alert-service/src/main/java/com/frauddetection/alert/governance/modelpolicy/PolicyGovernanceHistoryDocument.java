package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(PolicyGovernanceHistoryDocument.COLLECTION)
public record PolicyGovernanceHistoryDocument(
        @Id String id,
        String proposalId,
        long version,
        String action,
        PolicyGovernanceState fromStatus,
        PolicyGovernanceState toStatus,
        String actorId,
        String operationId,
        String commandSha256,
        String draftSha256,
        String approvalEvidenceReference,
        Instant committedAt,
        String rationale
) {
    public static final String COLLECTION = "model_policy_governance_history";

    public PolicyGovernanceHistoryDocument(String id, String proposalId, long version, String action,
            PolicyGovernanceState fromStatus, PolicyGovernanceState toStatus, String actorId,
            String operationId, String commandSha256, String draftSha256,
            String approvalEvidenceReference, Instant committedAt) {
        this(id, proposalId, version, action, fromStatus, toStatus, actorId, operationId,
                commandSha256, draftSha256, approvalEvidenceReference, committedAt, null);
    }

    PolicyGovernanceHistoryDocument withRationale(String signedRationale) {
        return new PolicyGovernanceHistoryDocument(id, proposalId, version, action, fromStatus, toStatus,
                actorId, operationId, commandSha256, draftSha256, approvalEvidenceReference,
                committedAt, signedRationale);
    }
}
