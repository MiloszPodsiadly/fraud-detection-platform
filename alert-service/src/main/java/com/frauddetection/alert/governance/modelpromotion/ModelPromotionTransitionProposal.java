package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModelPromotionTransitionProposal(
        ExactModelArtifactIdentity artifactIdentity,
        ModelGovernanceScope governanceScope,
        ModelPromotionIntendedUse intendedUse,
        ModelPromotionLifecycleState sourceState,
        long expectedRevision,
        ModelPromotionLifecycleState requestedTargetState,
        String operationId
) {

    private static final Pattern OPERATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public ModelPromotionTransitionProposal {
        Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        Objects.requireNonNull(sourceState, "sourceState is required");
        Objects.requireNonNull(requestedTargetState, "requestedTargetState is required");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        if (operationId == null || !OPERATION_ID.matcher(operationId).matches()) {
            throw new IllegalArgumentException("operationId is invalid");
        }
    }
}
