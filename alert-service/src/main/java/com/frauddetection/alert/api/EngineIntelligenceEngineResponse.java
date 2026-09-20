package com.frauddetection.alert.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.MlModelIdentity;

import java.util.List;
import java.util.Objects;

public record EngineIntelligenceEngineResponse(
        String engineId,
        FraudEngineType engineType,
        EngineIntelligenceEngineStatusResponse status,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        List<String> reasonCodes,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        MlModelIdentity modelIdentity
) {

    public EngineIntelligenceEngineResponse {
        Objects.requireNonNull(status, "status is required");
        EngineIntelligenceEngineResult result = new EngineIntelligenceEngineResult(
                engineId,
                engineType,
                contractStatus(status),
                riskLevel,
                scoreBucket,
                reasonCodes == null ? List.of() : reasonCodes,
                modelIdentity
        );
        engineId = result.engineId();
        engineType = result.engineType();
        riskLevel = result.riskLevel();
        scoreBucket = result.scoreBucket();
        reasonCodes = result.reasonCodes();
        modelIdentity = result.modelIdentity();
    }

    public EngineIntelligenceEngineResponse(
            String engineId,
            FraudEngineType engineType,
            EngineIntelligenceEngineStatusResponse status,
            RiskLevel riskLevel,
            EngineIntelligenceScoreBucket scoreBucket,
            List<String> reasonCodes
    ) {
        this(engineId, engineType, status, riskLevel, scoreBucket, reasonCodes, null);
    }

    private static FraudEngineStatus contractStatus(EngineIntelligenceEngineStatusResponse status) {
        return switch (status) {
            case AVAILABLE -> FraudEngineStatus.AVAILABLE;
            case TIMEOUT -> FraudEngineStatus.TIMEOUT;
            case DEGRADED -> FraudEngineStatus.DEGRADED;
            case NOT_APPLICABLE -> FraudEngineStatus.SKIPPED;
            case UNAVAILABLE -> FraudEngineStatus.UNAVAILABLE;
        };
    }
}
