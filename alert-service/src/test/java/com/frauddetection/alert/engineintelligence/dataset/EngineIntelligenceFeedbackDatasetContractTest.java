package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceFeedbackDatasetContractTest {

    @Test
    void confirmedFraudMapsToAnalystConfirmedFraud() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.map(AnalystDecision.CONFIRMED_FRAUD).evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.ANALYST_CONFIRMED_FRAUD);
    }

    @Test
    void markedLegitimateMapsToAnalystMarkedLegitimate() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.map(AnalystDecision.MARKED_LEGITIMATE).evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.ANALYST_MARKED_LEGITIMATE);
    }

    @Test
    void inconclusiveMapsToNotEvaluationEligible() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.mapWireValue("INCONCLUSIVE").evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE);
    }

    @Test
    void needsMoreInfoMapsToNotEvaluationEligible() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.mapWireValue("NEEDS_MORE_INFO").evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE);
    }

    @Test
    void missingDecisionMapsToNotEvaluationEligible() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.map(null).evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE);
    }

    @Test
    void unknownDecisionMapsToNotEvaluationEligible() {
        EngineIntelligenceFeedbackDatasetLabelMapper.MappedLabel label =
                EngineIntelligenceFeedbackDatasetLabelMapper.mapWireValue("SOMETHING_ELSE");

        assertThat(label.evaluationLabel()).isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE);
        assertThat(label.labelSource()).isEqualTo(EngineIntelligenceFeedbackDatasetLabelSource.UNKNOWN_ALERT_DECISION);
    }

    @Test
    void notEvaluationEligibleIsNeverNegative() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.mapWireValue("INCONCLUSIVE").evaluationLabel())
                .isNotEqualTo(EngineIntelligenceFeedbackDatasetLabel.ANALYST_MARKED_LEGITIMATE);
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.map(null).evaluationLabel())
                .isNotEqualTo(EngineIntelligenceFeedbackDatasetLabel.ANALYST_MARKED_LEGITIMATE);
    }

    @Test
    void labelsAreNotNamedGroundTruth() {
        assertThat(EngineIntelligenceFeedbackDatasetRecord.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("evaluationLabel")
                .doesNotContain("groundTruth", "confirmedTruth");
    }

    @Test
    void labelsAreNotNamedModelTrainingLabel() {
        assertThat(EngineIntelligenceFeedbackDatasetRecord.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("trainingLabel", "modelTrainingLabel");
    }

    @Test
    void labelNamesDoNotExposeGroundTruth() {
        assertThat(EngineIntelligenceFeedbackDatasetLabel.class.getEnumConstants())
                .extracting(Enum::name)
                .allSatisfy(name -> assertThat(name).doesNotContain("GROUND", "TRUTH"));
    }

    @Test
    void labelNamesDoNotExposeTrainingLabel() {
        assertThat(EngineIntelligenceFeedbackDatasetLabel.class.getEnumConstants())
                .extracting(Enum::name)
                .allSatisfy(name -> assertThat(name).doesNotContain("TRAINING", "LABEL"));
    }

    @Test
    void labelNamesDoNotExposeFinalDecision() {
        assertThat(EngineIntelligenceFeedbackDatasetLabel.class.getEnumConstants())
                .extracting(Enum::name)
                .allSatisfy(name -> assertThat(name).doesNotContain("FINAL", "DECISION"));
    }

    @Test
    void rawAlertAnalystDecisionIsNotExported() {
        assertThat(EngineIntelligenceFeedbackDatasetRecord.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("alertAnalystDecision", "analystDecision");
    }

    @Test
    void transactionReferenceIsBounded() {
        String reference = EngineIntelligenceFeedbackDatasetSafety.transactionReference("internal-txn-1");

        assertThat(reference).startsWith("txnref-").hasSize(39);
    }

    @Test
    void rawPaymentTransactionIdIsRejectedOrNotRepresented() {
        assertThatThrownBy(() -> new EngineIntelligenceFeedbackDatasetRecord(
                "eval-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "payment-txn-raw-123",
                Instant.parse("2026-06-01T00:00:00Z"),
                EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE,
                EngineIntelligenceFeedbackDatasetLabelSource.MISSING_ALERT_DECISION,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                EngineIntelligenceFeedbackDatasetProjectionStatus.MISSING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externalTransactionIdIsRejectedOrNotRepresented() {
        assertThatThrownBy(() -> EngineIntelligenceFeedbackDatasetSafety.requireTransactionReference("txn-ext-123"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
