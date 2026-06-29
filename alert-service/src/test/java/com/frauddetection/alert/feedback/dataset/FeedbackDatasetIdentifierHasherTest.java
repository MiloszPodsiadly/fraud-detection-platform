package com.frauddetection.alert.feedback.dataset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackDatasetIdentifierHasherTest {

    @Test
    void evaluationRecordIdIsDeterministic() {
        assertThat(FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-1"))
                .isEqualTo(FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-1"));
    }

    @Test
    void transactionReferenceIsDeterministic() {
        assertThat(FeedbackDatasetIdentifierHasher.transactionReference("txn-1"))
                .isEqualTo(FeedbackDatasetIdentifierHasher.transactionReference("txn-1"));
    }

    @Test
    void differentInputsCreateDifferentReferences() {
        assertThat(FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-1"))
                .isNotEqualTo(FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-2"));
        assertThat(FeedbackDatasetIdentifierHasher.transactionReference("txn-1"))
                .isNotEqualTo(FeedbackDatasetIdentifierHasher.transactionReference("txn-2"));
    }

    @Test
    void referencesDoNotExposeRawSourceIds() {
        assertThat(FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-raw-123"))
                .startsWith("eval_")
                .doesNotContain("feedback-raw-123");
        assertThat(FeedbackDatasetIdentifierHasher.transactionReference("txn-raw-123"))
                .startsWith("txnref_")
                .doesNotContain("txn-raw-123");
    }

    @Test
    void nullOrBlankSourceIdFailsExplicitly() {
        assertThatThrownBy(() -> FeedbackDatasetIdentifierHasher.evaluationRecordId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedbackDatasetIdentifierHasher.transactionReference(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
