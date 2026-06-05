package com.frauddetection.alert.engineintelligence.feedback;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFeedbackDocumentIndexTest {

    @Test
    void feedbackDocumentDefinesTransactionSubmittedFeedbackReadIndex() {
        CompoundIndexes indexes = EngineIntelligenceFeedbackDocument.class.getAnnotation(CompoundIndexes.class);

        assertThat(indexes.value()).anySatisfy(index -> {
            assertThat(index.name()).isEqualTo("engine_intelligence_feedback_transaction_submitted_feedback_idx");
            assertThat(index.def())
                    .contains("'transactionId': 1")
                    .contains("'submittedAt': -1")
                    .contains("'feedbackId': 1");
        });
    }

    @Test
    void feedbackDocumentKeepsIdempotencyUniqueIndex() {
        CompoundIndexes indexes = EngineIntelligenceFeedbackDocument.class.getAnnotation(CompoundIndexes.class);

        assertThat(Arrays.stream(indexes.value()).map(org.springframework.data.mongodb.core.index.CompoundIndex::name))
                .contains("engine_intelligence_feedback_idempotency_idx");
        assertThat(Arrays.stream(indexes.value())
                .filter(index -> "engine_intelligence_feedback_idempotency_idx".equals(index.name()))
                .findFirst())
                .isPresent()
                .get()
                .satisfies(index -> assertThat(index.unique()).isTrue());
    }
}
