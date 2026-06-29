package com.frauddetection.alert.feedback.dataset;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackDatasetRecordContractTest {

    @Test
    void datasetRecordContainsOnlyAllowedFieldNames() {
        assertThat(FeedbackDatasetRecord.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "datasetVersion",
                        "evaluationRecordId",
                        "transactionReference",
                        "feedbackLabel",
                        "evaluationLabel",
                        "decisionReasonCodes",
                        "feedbackCreatedAt",
                        "fraudScore",
                        "riskLevel",
                        "alertRecommended",
                        "engineIntelligenceStatus",
                        "agreementStatus",
                        "riskMismatchStatus",
                        "scoreDeltaBucket",
                        "analystRecommendationStatus",
                        "analystRecommendation",
                        "analystRecommendationVersion",
                        "analystRecommendationGeneratedAt",
                        "analystRecommendationReasonCodes",
                        "scoredAt",
                        "transactionTimestamp"
                );
    }

    @Test
    void datasetRecordDoesNotContainForbiddenRawOrDecisionFields() {
        assertThat(FeedbackDatasetRecord.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain(
                        "transactionId",
                        "feedbackId",
                        "customerId",
                        "correlationId",
                        "createdBy",
                        "notes",
                        "rawNotes",
                        "analystDecision",
                        "labelSource",
                        "feedbackStatus",
                        "groundTruth",
                        "trainingLabel",
                        "finalDecision",
                        "paymentDecision",
                        "paymentAuthorization",
                        "token",
                        "secret",
                        "password"
                );
    }

    @Test
    void evaluationLabelDoesNotContainGroundTruthOrTrainingNames() {
        assertThat(FeedbackEvaluationLabel.class.getEnumConstants())
                .extracting(Enum::name)
                .containsExactly("POSITIVE_FRAUD", "NEGATIVE_LEGITIMATE")
                .allSatisfy(name -> assertThat(name)
                        .doesNotContain("GROUND", "TRUTH", "TRAINING", "FINAL", "PAYMENT"));
    }
}
