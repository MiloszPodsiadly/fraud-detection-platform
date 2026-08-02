package com.frauddetection.alert.api;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;

import java.util.List;
import java.util.Objects;

public final class EngineIntelligenceResponseStatusPolicy {
    private static final String RULES = FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID;
    private static final String ML = FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID;
    private static final String VELOCITY = FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID;

    private EngineIntelligenceResponseStatusPolicy() {
    }

    public static EngineIntelligenceResponseStatus derive(
            List<EngineIntelligenceEngineResponse> engines,
            List<EngineIntelligenceWarningResponse> warnings
    ) {
        List<EngineIntelligenceEngineResponse> safeEngines = engines == null ? List.of() : engines;
        List<EngineIntelligenceWarningResponse> safeWarnings = warnings == null ? List.of() : warnings;
        if (!safeWarnings.isEmpty()) {
            return EngineIntelligenceResponseStatus.DEGRADED;
        }
        EngineIntelligenceEngineStatusResponse rules = statusFor(safeEngines, RULES);
        EngineIntelligenceEngineStatusResponse ml = statusFor(safeEngines, ML);
        if (rules != EngineIntelligenceEngineStatusResponse.AVAILABLE
                || ml != EngineIntelligenceEngineStatusResponse.AVAILABLE) {
            return EngineIntelligenceResponseStatus.DEGRADED;
        }
        EngineIntelligenceEngineStatusResponse velocity = statusFor(safeEngines, VELOCITY);
        if (velocity == null
                || velocity == EngineIntelligenceEngineStatusResponse.AVAILABLE
                || velocity == EngineIntelligenceEngineStatusResponse.NOT_APPLICABLE) {
            return EngineIntelligenceResponseStatus.AVAILABLE;
        }
        return EngineIntelligenceResponseStatus.DEGRADED;
    }

    private static EngineIntelligenceEngineStatusResponse statusFor(
            List<EngineIntelligenceEngineResponse> engines,
            String engineId
    ) {
        return engines.stream()
                .filter(engine -> engineId.equals(Objects.requireNonNull(engine, "engines must not contain null entries").engineId()))
                .findFirst()
                .map(EngineIntelligenceEngineResponse::status)
                .orElse(null);
    }
}
