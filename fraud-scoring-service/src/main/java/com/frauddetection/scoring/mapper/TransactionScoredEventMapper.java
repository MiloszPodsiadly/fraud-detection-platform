package com.frauddetection.scoring.mapper;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class TransactionScoredEventMapper {

    public TransactionScoredEvent toEvent(
            FraudScoringRequest scoringRequest,
            FraudScoreResult scoreResult,
            Optional<EngineIntelligenceSummary> engineIntelligence,
            MlPredictionEvidenceOmissionReason omissionReason,
            AnalystRecommendationResult analystRecommendation
    ) {
        return toEvent(
                scoringRequest,
                scoreResult,
                engineIntelligence,
                Optional.empty(),
                Optional.of(Objects.requireNonNull(omissionReason, "omissionReason is required")),
                analystRecommendation
        );
    }

    public TransactionScoredEvent toEvent(
            FraudScoringRequest scoringRequest,
            FraudScoreResult scoreResult,
            Optional<EngineIntelligenceSummary> engineIntelligence,
            Optional<MlPredictionEvidence> mlPredictionEvidence,
            Optional<MlPredictionEvidenceOmissionReason> mlPredictionEvidenceOmissionReason,
            AnalystRecommendationResult analystRecommendation
    ) {
        Objects.requireNonNull(engineIntelligence, "engineIntelligence is required");
        Objects.requireNonNull(mlPredictionEvidence, "mlPredictionEvidence is required");
        Objects.requireNonNull(
                mlPredictionEvidenceOmissionReason,
                "mlPredictionEvidenceOmissionReason is required"
        );
        if (mlPredictionEvidence.isPresent() == mlPredictionEvidenceOmissionReason.isPresent()) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_REQUIRES_EXACTLY_ONE_OUTCOME");
        }
        TransactionEnrichedEvent event = scoringRequest.event();
        return new TransactionScoredEvent(
                UUID.randomUUID().toString(),
                event.transactionId(),
                event.correlationId(),
                event.customerId(),
                event.accountId(),
                Instant.now(),
                event.transactionTimestamp(),
                event.transactionAmount(),
                event.merchantInfo(),
                event.deviceInfo(),
                event.locationInfo(),
                event.customerContext(),
                scoreResult.fraudScore(),
                scoreResult.riskLevel(),
                scoreResult.scoringStrategy(),
                scoreResult.modelName(),
                scoreResult.modelVersion(),
                scoreResult.inferenceTimestamp(),
                scoreResult.reasonCodes(),
                scoreResult.scoreDetails(),
                scoreResult.featureSnapshot(),
                scoreResult.alertRecommended(),
                scoreResult.scoringEvidence(),
                engineIntelligence.orElse(null),
                mlPredictionEvidence.orElse(null),
                mlPredictionEvidenceOmissionReason.orElse(null),
                analystRecommendation
        );
    }
}
