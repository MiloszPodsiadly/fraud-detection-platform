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
    void confirmedFraudMapsToPositive() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.map(AnalystDecision.CONFIRMED_FRAUD).evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.POSITIVE);
    }

    @Test
    void markedLegitimateMapsToNegative() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.map(AnalystDecision.MARKED_LEGITIMATE).evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NEGATIVE);
    }

    @Test
    void inconclusiveMapsToNonTraining() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.mapWireValue("INCONCLUSIVE").evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING);
    }

    @Test
    void needsMoreInfoMapsToNonTraining() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.mapWireValue("NEEDS_MORE_INFO").evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING);
    }

    @Test
    void missingDecisionMapsToNonTraining() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.map(null).evaluationLabel())
                .isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING);
    }

    @Test
    void unknownDecisionMapsToNonTraining() {
        EngineIntelligenceFeedbackDatasetLabelMapper.MappedLabel label =
                EngineIntelligenceFeedbackDatasetLabelMapper.mapWireValue("SOMETHING_ELSE");

        assertThat(label.evaluationLabel()).isEqualTo(EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING);
        assertThat(label.labelSource()).isEqualTo(EngineIntelligenceFeedbackDatasetLabelSource.UNKNOWN_ALERT_DECISION);
    }

    @Test
    void nonTrainingIsNeverNegative() {
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.mapWireValue("INCONCLUSIVE").evaluationLabel())
                .isNotEqualTo(EngineIntelligenceFeedbackDatasetLabel.NEGATIVE);
        assertThat(EngineIntelligenceFeedbackDatasetLabelMapper.map(null).evaluationLabel())
                .isNotEqualTo(EngineIntelligenceFeedbackDatasetLabel.NEGATIVE);
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
                EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING,
                EngineIntelligenceFeedbackDatasetLabelSource.MISSING_ALERT_DECISION,
                null,
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
