package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(PolicyGovernanceOperationDocument.COLLECTION)
public record PolicyGovernanceOperationDocument(
        @Id String id,
        String proposalId,
        String commandSha256,
        String actorId,
        PolicyProposalDocument result,
        Instant committedAt
) {
    public static final String COLLECTION = "model_policy_governance_operations";
}
