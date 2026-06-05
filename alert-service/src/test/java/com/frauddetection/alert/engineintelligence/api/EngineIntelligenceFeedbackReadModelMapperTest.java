package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFeedbackReadModelMapperTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-06-04T10:15:30Z");

    private final EngineIntelligenceFeedbackReadModelMapper mapper = new EngineIntelligenceFeedbackReadModelMapper();

    @Test
    void mapperCopiesAllowedFields() {
        EngineIntelligenceFeedbackReadModel model = mapper.map("txn-1", List.of(document("feedback-1")), 25, false);

        assertThat(model.transactionId()).isEqualTo("txn-1");
        assertThat(model.page()).isEqualTo(new EngineIntelligenceFeedbackPage(25, false));
        assertThat(model.feedback()).singleElement().satisfies(entry -> {
            assertThat(entry.feedbackId()).isEqualTo("feedback-1");
            assertThat(entry.engineIntelligenceAvailable()).isTrue();
            assertThat(entry.feedbackType()).isEqualTo(EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS);
            assertThat(entry.usefulness()).isEqualTo(EngineIntelligenceFeedbackUsefulness.HELPFUL);
            assertThat(entry.accuracyAssessment()).isEqualTo(EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT);
            assertThat(entry.selectedReasonCodes()).containsExactly("HIGH_VELOCITY");
            assertThat(entry.submittedAt()).isEqualTo(SUBMITTED_AT);
        });
    }

    @Test
    void readModelDoesNotExposeInternalFields() {
        assertThat(componentNames(EngineIntelligenceFeedbackReadModel.class))
                .containsExactly("transactionId", "feedback", "page");
        assertThat(componentNames(EngineIntelligenceFeedbackEntryReadModel.class))
                .containsExactly(
                        "feedbackId",
                        "engineIntelligenceAvailable",
                        "feedbackType",
                        "usefulness",
                        "accuracyAssessment",
                        "selectedReasonCodes",
                        "submittedAt"
                )
                .doesNotContain(
                        "submittedBy",
                        "idempotencyKeyHash",
                        "requestPayloadHash",
                        "correlationId",
                        "createdAt",
                        "auditMetadata",
                        "rawPayload",
                        "modelTrainingLabel",
                        "groundTruth",
                        "ruleUpdateRequest"
                );
    }

    @Test
    void mapperDefensivelyCopiesSelectedReasonCodes() {
        List<String> mutableReasonCodes = new ArrayList<>(List.of("HIGH_VELOCITY"));
        EngineIntelligenceFeedbackEntryReadModel entry = new EngineIntelligenceFeedbackEntryReadModel(
                "feedback-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                mutableReasonCodes,
                SUBMITTED_AT
        );

        mutableReasonCodes.add("AFTER_MAPPING");

        assertThat(entry.selectedReasonCodes()).containsExactly("HIGH_VELOCITY");
    }

    private List<String> componentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private EngineIntelligenceFeedbackDocument document(String feedbackId) {
        return new EngineIntelligenceFeedbackDocument(
                feedbackId,
                "txn-1",
                true,
                EngineIntelligenceFeedbackType.ENGINE_INTELLIGENCE_USEFULNESS,
                EngineIntelligenceFeedbackUsefulness.HELPFUL,
                EngineIntelligenceFeedbackAccuracyAssessment.SIGNALS_LOOK_CORRECT,
                List.of("HIGH_VELOCITY"),
                "analyst-1",
                SUBMITTED_AT,
                "correlation-1",
                "idempotency-hash-1",
                "payload-hash-1",
                Instant.parse("2026-06-04T10:16:30Z")
        );
    }
}
