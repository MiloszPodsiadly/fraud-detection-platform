package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackDatasetJsonlWriterTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final Instant BUILT_AT = Instant.parse("2026-06-02T12:00:00Z");

    @Test
    void metadataLineIsFirst() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result(List.of(record())));

        assertThat(jsonl.lines().findFirst().orElseThrow())
                .contains("\"type\":\"DATASET_METADATA\"")
                .contains("\"datasetVersion\":\"feedback-dataset-v1\"")
                .contains("\"timeBasis\":\"FEEDBACK_CREATED_AT\"");
    }

    @Test
    void oneDatasetRecordSerializesToOneLineAfterMetadata() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result(List.of(record())));

        assertThat(jsonl.lines()).hasSize(2);
        assertThat(jsonl.lines().skip(1).findFirst().orElseThrow())
                .contains("\"type\":\"DATASET_RECORD\"")
                .contains("\"evaluationLabel\":\"POSITIVE_FRAUD\"");
    }

    @Test
    void jsonlOutputIsDeterministicForSameResult() {
        FeedbackDatasetJsonlWriter writer = new FeedbackDatasetJsonlWriter();
        FeedbackDatasetBuildResult result = result(List.of(record()));

        assertThat(writer.writeJsonl(result)).isEqualTo(writer.writeJsonl(result));
    }

    @Test
    void successfulEmptyDatasetHasMetadataOnly() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result(List.of()));

        assertThat(jsonl.lines()).hasSize(1);
        assertThat(jsonl)
                .contains("\"recordsReturned\":0")
                .contains("\"failureReason\":\"NONE\"");
    }

    @Test
    void failedBuildHasMetadataOnlyWithFailureReason() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(failedResult());

        assertThat(jsonl.lines()).hasSize(1);
        assertThat(jsonl)
                .contains("\"type\":\"DATASET_METADATA\"")
                .contains("\"failureReason\":\"FEEDBACK_STORE_UNAVAILABLE\"")
                .doesNotContain("\"type\":\"DATASET_RECORD\"");
    }

    @Test
    void jsonlDoesNotContainRawSourceIdentifiers() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result(List.of(record())));

        assertThat(jsonl)
                .doesNotContain("feedback-raw-1")
                .doesNotContain("txn-raw-1");
    }

    @Test
    void jsonlDoesNotContainForbiddenFieldNames() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result(List.of(record())));

        assertThat(jsonl)
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
                        "rawMlRequest",
                        "rawMlResponse",
                        "rawFeatureVector",
                        "rawEvidence",
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

    private FeedbackDatasetBuildResult result(List<FeedbackDatasetRecord> records) {
        return new FeedbackDatasetBuildResult(
                FeedbackDatasetBuilder.DATASET_VERSION,
                BUILT_AT,
                FeedbackDatasetTimeBasis.FEEDBACK_CREATED_AT,
                FROM,
                TO,
                records.size(),
                records.size(),
                0,
                0,
                0,
                false,
                FeedbackDatasetBuildFailureReason.NONE,
                records
        );
    }

    private FeedbackDatasetBuildResult failedResult() {
        return new FeedbackDatasetBuildResult(
                FeedbackDatasetBuilder.DATASET_VERSION,
                BUILT_AT,
                FeedbackDatasetTimeBasis.FEEDBACK_CREATED_AT,
                FROM,
                TO,
                0,
                0,
                0,
                0,
                0,
                false,
                FeedbackDatasetBuildFailureReason.FEEDBACK_STORE_UNAVAILABLE,
                List.of()
        );
    }

    private FeedbackDatasetRecord record() {
        return new FeedbackDatasetRecord(
                FeedbackDatasetBuilder.DATASET_VERSION,
                FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-raw-1"),
                FeedbackDatasetIdentifierHasher.transactionReference("txn-raw-1"),
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("ANALYST_CONFIRMED_FRAUD"),
                FROM,
                null,
                null,
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
                null,
                null
        );
    }
}
