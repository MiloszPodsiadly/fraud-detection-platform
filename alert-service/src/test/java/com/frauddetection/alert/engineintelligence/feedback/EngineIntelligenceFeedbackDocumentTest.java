package com.frauddetection.alert.engineintelligence.feedback;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceFeedbackDocumentTest {

    @Test
    void constructorDefensivelyCopiesSelectedReasonCodes() {
        List<String> input = new ArrayList<>();
        input.add("HIGH_VELOCITY");

        EngineIntelligenceFeedbackDocument document = document(input);

        input.add("LATE_MUTATION");

        assertThat(document.getSelectedReasonCodes()).containsExactly("HIGH_VELOCITY");
    }

    @Test
    void selectedReasonCodesGetterDoesNotExposeMutableInternalState() {
        EngineIntelligenceFeedbackDocument document = document(List.of("HIGH_VELOCITY"));

        assertThatThrownBy(() -> document.getSelectedReasonCodes().add("MUTATION"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(document.getSelectedReasonCodes()).containsExactly("HIGH_VELOCITY");
    }

    @Test
    void nullSelectedReasonCodesBecomesEmptyList() {
        EngineIntelligenceFeedbackDocument document = document(null);

        assertThat(document.getSelectedReasonCodes()).isEmpty();
    }

    private EngineIntelligenceFeedbackDocument document(List<String> selectedReasonCodes) {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");
        return new EngineIntelligenceFeedbackDocument(
                "feedback-1",
                "txn-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                selectedReasonCodes,
                "analyst-1",
                now,
                "corr-1",
                "idempotency-hash",
                "payload-hash",
                now
        );
    }
}
