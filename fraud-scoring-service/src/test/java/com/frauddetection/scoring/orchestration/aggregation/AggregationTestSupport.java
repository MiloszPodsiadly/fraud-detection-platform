package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;

import java.time.Instant;
import java.util.List;

final class AggregationTestSupport {
    static final Instant GENERATED_AT = Instant.parse("2026-05-31T10:00:00Z");

    private AggregationTestSupport() {
    }

    static NormalizedFraudEngineResult normalized(
            String engineId,
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            String... reasonCodes
    ) {
        return new NormalizedFraudEngineResult(
                engineId,
                engineType(engineId),
                status,
                score,
                riskLevel,
                status == FraudEngineStatus.AVAILABLE ? FraudEngineConfidence.MEDIUM : FraudEngineConfidence.UNKNOWN,
                List.of(reasonCodes),
                List.of(),
                List.of(),
                0L
        );
    }

    static FraudEngineResult raw(
            String engineId,
            FraudEngineStatus status,
            Double score,
            RiskLevel riskLevel,
            List<String> reasonCodes,
            List<FraudEngineContribution> contributions,
            List<FraudEngineEvidence> evidence
    ) {
        return new FraudEngineResult(
                engineId,
                engineType(engineId),
                engineId.equals("rules.primary") ? "java" : "python",
                status,
                score,
                riskLevel,
                status == FraudEngineStatus.AVAILABLE ? FraudEngineConfidence.MEDIUM : FraudEngineConfidence.UNKNOWN,
                reasonCodes,
                contributions,
                evidence,
                0L,
                null,
                null,
                status == FraudEngineStatus.AVAILABLE ? null : reasonCodes.getFirst(),
                GENERATED_AT
        );
    }

    static FraudEngineResult available(String engineId, double score, RiskLevel riskLevel, String... reasonCodes) {
        return raw(engineId, FraudEngineStatus.AVAILABLE, score, riskLevel, List.of(reasonCodes), List.of(), List.of());
    }

    static FraudEngineResult unavailable(String engineId, FraudEngineStatus status, String reasonCode) {
        return raw(engineId, status, null, null, List.of(reasonCode), List.of(), List.of());
    }

    static FraudScoringOrchestrationResult orchestration(FraudEngineResult... results) {
        return new FraudScoringOrchestrationResult(
                FraudScoringOrchestrationStatus.COMPLETE,
                List.of(results),
                List.of(),
                GENERATED_AT
        );
    }

    private static FraudEngineType engineType(String engineId) {
        return engineId.equals("rules.primary") ? FraudEngineType.RULES : FraudEngineType.ML_MODEL;
    }
}
