package com.frauddetection.alert.governance.modelcard;

import java.util.Optional;

final class EmptyModelCardProvider implements ModelCardProvider {

    @Override
    public Optional<VerifiedModelCardArtifact> currentArtifact() {
        return Optional.empty();
    }
}
