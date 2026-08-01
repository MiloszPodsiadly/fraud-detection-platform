package com.frauddetection.scoring.engine;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.util.List;
import java.util.Objects;

public record FraudSignalEvaluation(
        FraudEngineStatus status,
        Double score,
        RiskLevel riskLevel,
        FraudEngineConfidence confidence,
        List<String> reasonCodes,
        List<FraudEngineContribution> contributions,
        List<FraudEngineEvidence> evidence,
        String modelName,
        String modelVersion,
        String statusReason
) {
    public FraudSignalEvaluation {
        Objects.requireNonNull(status, "status is required");
        confidence = confidence == null ? FraudEngineConfidence.UNKNOWN : confidence;
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
