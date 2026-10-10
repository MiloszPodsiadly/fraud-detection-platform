package com.frauddetection.alert.feedback.assembly;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.feedback.FeedbackLabelSource;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.feedback.FraudFeedbackStatus;
import com.frauddetection.alert.feedback.validation.ValidatedFraudFeedback;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class FraudFeedbackRecordAssembler {

    public void assemble(
            FraudFeedbackRecord record,
            ScoredTransaction transaction,
            ValidatedFraudFeedback feedback,
            String actor,
            Instant createdAt
    ) {
        record.setFeedbackId("ffb-" + UUID.randomUUID());
        record.setTransactionId(transaction.transactionId());
        record.setCustomerId(transaction.customerId());
        record.setCorrelationId(transaction.correlationId());
        record.setAnalystDecision(feedback.analystDecision());
        record.setFeedbackLabel(feedback.feedbackLabel());
        record.setLabelSource(FeedbackLabelSource.ANALYST_REVIEW);
        record.setFeedbackStatus(FraudFeedbackStatus.RECORDED);
        record.setCreatedAt(createdAt);
        record.setCreatedBy(actor);
        record.setDecisionReasonCodes(feedback.decisionReasonCodes());
        record.setNotes(feedback.notes());
        record.setFraudScore(transaction.fraudScore());
        record.setRiskLevel(transaction.riskLevel());
        record.setAlertRecommended(transaction.alertRecommended());
        record.setScoredAt(transaction.scoredAt());
        record.setTransactionTimestamp(transaction.transactionTimestamp());
        record.setMlPredictionEvidenceOmissionReason(transaction.mlPredictionEvidenceOmissionReason());
        snapshotAnalystRecommendation(record, transaction.analystRecommendation());
    }

    private void snapshotAnalystRecommendation(
            FraudFeedbackRecord record,
            AnalystRecommendationResult recommendation
    ) {
        AnalystRecommendationResult snapshot = recommendation == null
                ? AnalystRecommendationResult.absent()
                : recommendation;
        record.setAnalystRecommendationStatus(snapshot.status());
        record.setAnalystRecommendation(snapshot.recommendation());
        record.setAnalystRecommendationVersion(snapshot.recommendationVersion());
        record.setAnalystRecommendationGeneratedAt(snapshot.generatedAt());
        record.setAnalystRecommendationReasonCodes(snapshot.reasonCodes());
    }
}
