package com.frauddetection.alert.governance.exactmodelshadowperformance;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

final class EmptyExactModelShadowPerformanceProvider implements ExactModelShadowPerformanceProvider {
    @Override
    public Optional<JsonNode> find(ExactModelShadowPerformanceSelector selector) {
        return Optional.empty();
    }
}
