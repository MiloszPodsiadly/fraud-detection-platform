package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.util.Optional;

public interface ModelCardProvider {

    Optional<VerifiedModelCardArtifact> currentArtifact();

    default Optional<ModelCard> currentCard() {
        return currentArtifact().map(VerifiedModelCardArtifact::card);
    }

    Optional<VerifiedModelCardArtifact> findExact(ExactModelArtifactIdentity identity);
}
