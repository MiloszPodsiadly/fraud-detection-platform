package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.util.Objects;
import java.util.Optional;

public interface ModelCardProvider {

    Optional<VerifiedModelCardArtifact> currentArtifact();

    default Optional<ModelCard> currentCard() {
        return currentArtifact().map(VerifiedModelCardArtifact::card);
    }

    default Optional<VerifiedModelCardArtifact> findExact(ExactModelArtifactIdentity identity) {
        Objects.requireNonNull(identity, "identity is required");
        return currentArtifact().filter(artifact -> identity.equals(new ExactModelArtifactIdentity(
                artifact.card().subject().modelName(),
                artifact.card().subject().modelVersion(),
                artifact.card().subject().featureContractVersion(),
                artifact.card().subject().modelArtifactSha256()
        )));
    }
}
