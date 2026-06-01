package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineIntelligenceEngineResult(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        List<String> reasonCodes
) {
    public EngineIntelligenceEngineResult {
        Objects.requireNonNull(engineType, "engineType is required");
        EngineIntelligenceValuePolicy.requireEngineIdentity(engineId, engineType);
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(scoreBucket, "scoreBucket is required");
        if (status != FraudEngineStatus.AVAILABLE && riskLevel != null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_OPERATIONAL_STATUS_RISK_LEVEL_INVALID");
        }
        if (status != FraudEngineStatus.AVAILABLE && scoreBucket != EngineIntelligenceScoreBucket.UNAVAILABLE) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_OPERATIONAL_STATUS_SCORE_BUCKET_INVALID");
        }
        reasonCodes = EngineIntelligenceValuePolicy.copyBounded(
                reasonCodes,
                EngineIntelligenceValuePolicy.MAX_REASON_CODES_PER_ENGINE,
                "reasonCodes"
        );
        reasonCodes.forEach(EngineIntelligenceValuePolicy::requireReasonCode);
    }
}
