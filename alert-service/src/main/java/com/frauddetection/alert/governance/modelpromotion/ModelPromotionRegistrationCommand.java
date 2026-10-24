package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.util.Objects;

public record ModelPromotionRegistrationCommand(
        ExactModelArtifactIdentity artifactIdentity,
        ModelGovernanceScope governanceScope,
        ModelPromotionIntendedUse intendedUse,
        ModelPromotionOperationContext operationContext
) {
    public ModelPromotionRegistrationCommand {
        Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        Objects.requireNonNull(operationContext, "operationContext is required");
    }
}
