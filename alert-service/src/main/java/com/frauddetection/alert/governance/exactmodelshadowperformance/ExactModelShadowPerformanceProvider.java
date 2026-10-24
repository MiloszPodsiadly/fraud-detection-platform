package com.frauddetection.alert.governance.exactmodelshadowperformance;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

public interface ExactModelShadowPerformanceProvider {

    Optional<VerifiedExactModelShadowPerformanceArtifact> findArtifact(
            ExactModelShadowPerformanceSelector selector
    );

    default Optional<JsonNode> find(ExactModelShadowPerformanceSelector selector) {
        return findArtifact(selector).map(VerifiedExactModelShadowPerformanceArtifact::summary);
    }
}
