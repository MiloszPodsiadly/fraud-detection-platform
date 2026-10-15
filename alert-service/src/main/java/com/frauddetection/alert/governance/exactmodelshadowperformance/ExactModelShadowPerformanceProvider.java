package com.frauddetection.alert.governance.exactmodelshadowperformance;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

public interface ExactModelShadowPerformanceProvider {
    Optional<JsonNode> find(ExactModelShadowPerformanceSelector selector);
}
