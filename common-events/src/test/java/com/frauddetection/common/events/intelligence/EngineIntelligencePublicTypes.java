package com.frauddetection.common.events.intelligence;

import java.util.List;

final class EngineIntelligencePublicTypes {
    private EngineIntelligencePublicTypes() {
    }

    static List<Class<?>> records() {
        return List.of(
                EngineIntelligenceSummary.class,
                EngineIntelligenceEngineResult.class,
                EngineIntelligenceComparison.class,
                EngineIntelligenceDiagnosticSignal.class,
                EngineIntelligenceWarningSummary.class
        );
    }
}
