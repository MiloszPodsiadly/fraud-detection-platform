package com.frauddetection.alert.governance.exactmodelshadowperformance;

import java.util.Optional;

final class EmptyExactModelShadowPerformanceProvider implements ExactModelShadowPerformanceProvider {
    @Override
    public Optional<VerifiedExactModelShadowPerformanceArtifact> findArtifact(
            ExactModelShadowPerformanceSelector selector
    ) {
        return Optional.empty();
    }
}
