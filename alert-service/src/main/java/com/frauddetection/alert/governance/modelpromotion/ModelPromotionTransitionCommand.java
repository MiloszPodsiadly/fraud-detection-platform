package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModelPromotionTransitionCommand(
        ExactModelArtifactIdentity artifactIdentity,
        ModelGovernanceScope governanceScope,
        ModelPromotionIntendedUse intendedUse,
        ModelPromotionLifecycleState sourceState,
        long expectedRevision,
        ModelPromotionLifecycleState requestedTargetState,
        String operationId,
        ModelPromotionDecisionReference decisionReference
) {

    private static final Pattern OPERATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public ModelPromotionTransitionCommand {
        Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        Objects.requireNonNull(sourceState, "sourceState is required");
        Objects.requireNonNull(requestedTargetState, "requestedTargetState is required");
        Objects.requireNonNull(decisionReference, "decisionReference is required");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        if (operationId == null || !OPERATION_ID.matcher(operationId).matches()) {
            throw new IllegalArgumentException("operationId is invalid");
        }
    }
}
