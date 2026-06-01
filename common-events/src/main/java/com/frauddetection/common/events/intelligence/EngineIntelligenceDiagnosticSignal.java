package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineIntelligenceDiagnosticSignal(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus engineStatus,
        EngineIntelligenceSignalCategory signalCategory,
        @JsonInclude(JsonInclude.Include.NON_NULL) RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        String reasonCode
) {
    public EngineIntelligenceDiagnosticSignal {
        Objects.requireNonNull(engineType, "engineType is required");
        EngineIntelligenceValuePolicy.requireEngineIdentity(engineId, engineType);
        Objects.requireNonNull(engineStatus, "engineStatus is required");
        Objects.requireNonNull(signalCategory, "signalCategory is required");
        Objects.requireNonNull(scoreBucket, "scoreBucket is required");
        if (engineStatus != FraudEngineStatus.AVAILABLE && riskLevel != null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_OPERATIONAL_SIGNAL_RISK_LEVEL_INVALID");
        }
        if (signalCategory == EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL && riskLevel != null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_OPERATIONAL_SIGNAL_RISK_LEVEL_INVALID");
        }
        if (engineStatus != FraudEngineStatus.AVAILABLE && scoreBucket != EngineIntelligenceScoreBucket.UNAVAILABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_OPERATIONAL_SIGNAL_SCORE_BUCKET_INVALID");
        }
        if (signalCategory == EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL
                && scoreBucket != EngineIntelligenceScoreBucket.UNAVAILABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_OPERATIONAL_SIGNAL_SCORE_BUCKET_INVALID");
        }
        EngineIntelligenceValuePolicy.requireReasonCode(reasonCode);
    }
}
