package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class MlPredictionEvidenceProjectionTestSupport {

    static final Instant EVENT_CREATED_AT = Instant.parse("2026-10-03T10:15:31.987654Z");
    static final Instant EXECUTED_AT = Instant.parse("2026-10-03T10:15:30.123456Z");

    private MlPredictionEvidenceProjectionTestSupport() {
    }

    static TransactionScoredEvent event(String eventId, double score, String modelVersion) {
        return event(eventId, "txn-evidence-1", "corr-evidence-1", score, modelVersion, EVENT_CREATED_AT);
    }

    static TransactionScoredEvent eventWithoutEvidence(String eventId) {
        return eventWithoutEvidence(eventId, MlPredictionEvidenceOmissionReason.PREDICTION_NOT_ACCEPTED);
    }

    static TransactionScoredEvent eventWithoutEvidence(
            String eventId,
            MlPredictionEvidenceOmissionReason omissionReason
    ) {
        TransactionScoredEvent source = event(eventId, 0.8123d, "model-v1");
        return new TransactionScoredEvent(
                source.eventId(),
                source.transactionId(),
                source.correlationId(),
                source.customerId(),
                source.accountId(),
                source.createdAt(),
                source.transactionTimestamp(),
                source.transactionAmount(),
                source.merchantInfo(),
                source.deviceInfo(),
                source.locationInfo(),
                source.customerContext(),
                source.fraudScore(),
                source.riskLevel(),
                source.scoringStrategy(),
                source.modelName(),
                source.modelVersion(),
                source.inferenceTimestamp(),
                source.reasonCodes(),
                source.scoreDetails(),
                source.featureSnapshot(),
                source.alertRecommended(),
                source.scoringEvidence(),
                source.engineIntelligence(),
                null,
                omissionReason,
                source.analystRecommendation()
        );
    }

    static TransactionScoredEvent event(
            String eventId,
            String transactionId,
            String correlationId,
            double score,
            String modelVersion,
            Instant eventCreatedAt
    ) {
        MlModelIdentity identity = new MlModelIdentity(
                "python-logistic-fraud-model",
                modelVersion,
                "2026-05-30.feature-contract.v1"
        );
        EngineIntelligenceSummary summary = new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                EXECUTED_AT,
                List.of(
                        new EngineIntelligenceEngineResult(
                                "rules.primary",
                                FraudEngineType.RULES,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.LOW,
                                EngineIntelligenceScoreBucket.LOW,
                                List.of("HIGH_VELOCITY")
                        ),
                        new EngineIntelligenceEngineResult(
                                "ml.python.primary",
                                FraudEngineType.ML_MODEL,
                                FraudEngineStatus.AVAILABLE,
                                RiskLevel.HIGH,
                                EngineIntelligenceScoreBucket.from(FraudEngineStatus.AVAILABLE, score),
                                List.of("MODEL_HIGH_RISK"),
                                identity
                        )
                ),
                new EngineIntelligenceComparison(
                        EngineIntelligenceComparisonType.RULES_VS_ML,
                        List.of("rules.primary", "ml.python.primary"),
                        EngineIntelligenceAgreementStatus.DISAGREEMENT,
                        EngineIntelligenceRiskMismatchStatus.MATERIAL_RISK_MISMATCH,
                        EngineIntelligenceScoreDeltaBucket.LARGE
                ),
                List.of(),
                List.of()
        );
        MlPredictionEvidence evidence = new MlPredictionEvidence(
                score,
                RiskLevel.HIGH,
                identity,
                "a".repeat(64),
                EXECUTED_AT
        );
        return new TransactionScoredEvent(
                eventId,
                transactionId,
                correlationId,
                "cust-evidence-1",
                "acct-evidence-1",
                eventCreatedAt,
                eventCreatedAt.minusSeconds(1),
                null,
                null,
                null,
                null,
                null,
                0.82d,
                RiskLevel.HIGH,
                "RULE_BASED",
                "rule-based-engine",
                "v2",
                EXECUTED_AT,
                List.of("HIGH_VELOCITY"),
                Map.of(),
                Map.of(),
                true,
                List.of(),
                summary,
                evidence,
                null,
                null
        );
    }
}
