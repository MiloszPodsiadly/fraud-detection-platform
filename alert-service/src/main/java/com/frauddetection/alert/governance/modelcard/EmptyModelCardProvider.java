package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.util.Optional;

final class EmptyModelCardProvider implements ModelCardProvider {

    @Override
    public Optional<VerifiedModelCardArtifact> currentArtifact() {
        return Optional.empty();
    }

    @Override
    public Optional<VerifiedModelCardArtifact> findExact(ExactModelArtifactIdentity identity) {
        throw new ModelCardProviderUnavailableException("Exact Model Card resolution is unavailable.");
    }
}
