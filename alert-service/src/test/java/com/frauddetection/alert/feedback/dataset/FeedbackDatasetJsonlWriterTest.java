package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackDatasetJsonlWriterTest {

    @Test
    void buildResultRejectsUnsupportedDatasetVersion() {
        assertThatThrownBy(() -> result("feedback-dataset-v1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildResultRejectsUnreconciledPopulationCounts() {
        assertThatThrownBy(() -> new FeedbackDatasetBuildResult(
                FeedbackDatasetBuilder.DATASET_VERSION,
                BUILT_AT,
                FeedbackDatasetTimeBasis.FEEDBACK_CREATED_AT,
                FROM,
                TO,
                2,
                1,
                0,
                0,
                0,
                0,
                false,
                FeedbackDatasetBuildFailureReason.NONE,
                List.of(record())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset population counts must reconcile");
    }

    @Test
    void buildResultAcceptsSingleTruncationSentinelRow() {
        FeedbackDatasetBuildResult result = new FeedbackDatasetBuildResult(
                FeedbackDatasetBuilder.DATASET_VERSION,
                BUILT_AT,
                FeedbackDatasetTimeBasis.FEEDBACK_CREATED_AT,
                FROM,
                TO,
                2,
                1,
                0,
                0,
                0,
                0,
                true,
                FeedbackDatasetBuildFailureReason.NONE,
                List.of(record())
        );

        assertThat(result.truncated()).isTrue();
    }

    @Test
    void buildResultRejectsPopulationBeyondHardLimit() {
        assertThatThrownBy(() -> new FeedbackDatasetBuildResult(
                FeedbackDatasetBuilder.DATASET_VERSION,
                BUILT_AT,
                FeedbackDatasetTimeBasis.FEEDBACK_CREATED_AT,
                FROM,
                TO,
                1001,
                0,
                1001,
                0,
                0,
                0,
                false,
                FeedbackDatasetBuildFailureReason.NONE,
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset population exceeds the bounded record limit");
    }

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final Instant BUILT_AT = Instant.parse("2026-06-02T12:00:00Z");

    @Test
    void metadataLineIsFirst() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result(List.of(record())));

        assertThat(jsonl.lines().findFirst().orElseThrow())
                .contains("\"type\":\"DATASET_METADATA\"")
                .contains("\"datasetVersion\":\"feedback-dataset-v2\"")
                .contains("\"timeBasis\":\"FEEDBACK_CREATED_AT\"")
                .contains("\"skippedInvalidSourceRecordCount\":0");
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
    void metadataLineIsNotDatasetRecordEvaluationRow() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result(List.of(record())));

        List<String> lines = jsonl.lines().toList();

        assertThat(lines.getFirst()).contains("\"type\":\"DATASET_METADATA\"");
        assertThat(lines.getFirst()).doesNotContain("\"type\":\"DATASET_RECORD\"");
        assertThat(lines.subList(1, lines.size()))
                .allSatisfy(line -> assertThat(line).contains("\"type\":\"DATASET_RECORD\""));
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

    @Test
    void jsonlWritesMlModelIdentitySnapshotFields() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(result(List.of(recordWithMlIdentity())));

        assertThat(jsonl)
                .contains("\"rulesEvidenceStatus\":\"AVAILABLE\"")
                .contains("\"rulesRiskLevel\":\"LOW\"")
                .contains("\"mlPredictionEvidenceStatus\":\"AVAILABLE\"")
                .contains("\"mlPredictionScore\":0.8123")
                .contains("\"mlPredictionRiskLevel\":\"HIGH\"")
                .contains("\"mlPredictionExecutedAt\":\"2026-06-01T00:00:01Z\"")
                .contains("\"mlModelName\":\"python-logistic-fraud-model\"")
                .contains("\"mlModelVersion\":\"2026-06-25.v1\"")
                .contains("\"mlFeatureContractVersion\":\"feature-contract-v2\"");
    }

    private FeedbackDatasetBuildResult result(List<FeedbackDatasetRecord> records) {
        return result(FeedbackDatasetBuilder.DATASET_VERSION, records);
    }

    private FeedbackDatasetBuildResult result(String datasetVersion, List<FeedbackDatasetRecord> records) {
        return new FeedbackDatasetBuildResult(
                datasetVersion,
                BUILT_AT,
                FeedbackDatasetTimeBasis.FEEDBACK_CREATED_AT,
                FROM,
                TO,
                records.size(),
                records.size(),
                0,
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
                FeedbackDatasetRulesEvidenceStatus.UNAVAILABLE,
                null,
                FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT,
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED,
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

    private FeedbackDatasetRecord recordWithMlIdentity() {
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
                FeedbackDatasetRulesEvidenceStatus.AVAILABLE,
                RiskLevel.LOW,
                FeedbackDatasetMlPredictionEvidenceStatus.AVAILABLE,
                null,
                0.8123,
                RiskLevel.HIGH,
                Instant.parse("2026-06-01T00:00:01Z"),
                "python-logistic-fraud-model",
                "2026-06-25.v1",
                "feature-contract-v2",
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
