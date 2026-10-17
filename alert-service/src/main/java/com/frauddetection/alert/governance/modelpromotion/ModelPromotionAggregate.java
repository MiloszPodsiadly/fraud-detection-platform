package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.util.Objects;
import java.util.Optional;

public record ModelPromotionAggregate(
        ExactModelArtifactIdentity artifactIdentity,
        ModelGovernanceScope governanceScope,
        ModelPromotionIntendedUse intendedUse,
        ModelPromotionLifecycleState lifecycleState,
        long revision,
        String acceptedDecisionId
) {

    public ModelPromotionAggregate {
        Objects.requireNonNull(artifactIdentity, "artifactIdentity is required");
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        Objects.requireNonNull(lifecycleState, "lifecycleState is required");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (lifecycleState == ModelPromotionLifecycleState.CANDIDATE) {
            if (revision != 0 || acceptedDecisionId != null) {
                throw new IllegalArgumentException("CANDIDATE must be an undecided initial aggregate at revision zero");
            }
        } else if (revision == 0 || acceptedDecisionId == null || acceptedDecisionId.isBlank()) {
            throw new IllegalArgumentException("A transitioned aggregate requires revision and accepted decision identity");
        }
    }

    public static ModelPromotionAggregate registerCandidate(
            ExactModelArtifactIdentity artifactIdentity,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse
    ) {
        return new ModelPromotionAggregate(
                artifactIdentity,
                governanceScope,
                intendedUse,
                ModelPromotionLifecycleState.CANDIDATE,
                0,
                null
        );
    }

    public boolean authorizesRuntimeActivation() {
        return false;
    }

    public Optional<ModelPromotionChampionKey> championKey() {
        if (lifecycleState != ModelPromotionLifecycleState.CHAMPION) {
            return Optional.empty();
        }
        return Optional.of(new ModelPromotionChampionKey(governanceScope, intendedUse));
    }
}
