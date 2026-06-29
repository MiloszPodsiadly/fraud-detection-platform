package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackDatasetSchemaContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path SCHEMA = ROOT.resolve("docs/schemas/feedback_dataset_record.schema.json");
    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");
    private static final Instant BUILT_AT = Instant.parse("2026-06-02T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaFileExistsAndDocumentsEnvelopeLineTypes() throws Exception {
        String schema = Files.readString(SCHEMA);
        JsonNode root = objectMapper.readTree(schema);

        assertThat(SCHEMA).exists();
        assertThat(root.get("oneOf")).hasSize(2);
        assertThat(schema)
                .contains("DATASET_METADATA")
                .contains("DATASET_RECORD")
                .contains("skippedMissingRequiredFieldCount")
                .contains("skippedInvalidSourceRecordCount");
    }

    @Test
    void schemaRequiresDatasetRecordContractFields() throws Exception {
        String schema = Files.readString(SCHEMA);

        assertThat(schema)
                .contains(
                        "\"datasetVersion\"",
                        "\"evaluationRecordId\"",
                        "\"transactionReference\"",
                        "\"feedbackLabel\"",
                        "\"evaluationLabel\"",
                        "\"decisionReasonCodes\"",
                        "\"feedbackCreatedAt\""
                );
    }

    @Test
    void schemaDocumentsFeedbackLabelEvaluationLabelPairConstraints() throws Exception {
        String schema = Files.readString(SCHEMA);

        assertThat(schema)
                .contains("\"allOf\"")
                .contains("\"if\"")
                .contains("\"then\"")
                .contains("\"feedbackLabel\"")
                .contains("\"evaluationLabel\"")
                .contains("\"const\": \"CONFIRMED_FRAUD\"")
                .contains("\"const\": \"POSITIVE_FRAUD\"")
                .contains("\"const\": \"CONFIRMED_LEGITIMATE\"")
                .contains("\"const\": \"NEGATIVE_LEGITIMATE\"");
    }

    @Test
    void schemaKeepsDecisionReasonCodeLimitAtTen() throws Exception {
        String schema = Files.readString(SCHEMA);

        assertThat(schema).contains("\"maxItems\": 10");
    }

    @Test
    void schemaDoesNotAllowForbiddenDatasetFields() throws Exception {
        String schema = Files.readString(SCHEMA);

        assertThat(schema).doesNotContain(
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
    void generatedJsonlMetadataAndRecordLinesMatchDocumentedShape() throws Exception {
        List<String> lines = new FeedbackDatasetJsonlWriter()
                .writeJsonl(result(List.of(record())))
                .lines()
                .toList();

        JsonNode metadata = objectMapper.readTree(lines.getFirst());
        JsonNode recordLine = objectMapper.readTree(lines.get(1));
        JsonNode record = recordLine.get("record");

        assertThat(metadata.get("type").asString()).isEqualTo("DATASET_METADATA");
        assertThat(metadata.has("record")).isFalse();
        assertThat(metadata.has("failureReason")).isTrue();
        assertThat(metadata.has("skippedInvalidSourceRecordCount")).isTrue();
        assertThat(recordLine.get("type").asString()).isEqualTo("DATASET_RECORD");
        assertThat(recordLine.has("record")).isTrue();
        assertThat(record.get("datasetVersion").asString()).isEqualTo(FeedbackDatasetBuilder.DATASET_VERSION);
        assertThat(record.get("evaluationRecordId").asString()).startsWith("eval_");
        assertThat(record.get("transactionReference").asString()).startsWith("txnref_");
        assertThat(record.get("feedbackLabel").asString()).isEqualTo("CONFIRMED_FRAUD");
        assertThat(record.get("evaluationLabel").asString()).isEqualTo("POSITIVE_FRAUD");
        assertThat(record.get("decisionReasonCodes").get(0).asString()).isEqualTo("ANALYST_CONFIRMED_FRAUD");
        assertThat(record.get("feedbackCreatedAt").asString()).isEqualTo("2026-06-01T00:00:00Z");
    }

    @Test
    void schemaEquivalentContractAcceptsValidEvaluationPairs() {
        assertSchemaEquivalentRecordAccepted(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("ANALYST_CONFIRMED_FRAUD")
        );
        assertSchemaEquivalentRecordAccepted(
                FraudFeedbackLabel.CONFIRMED_LEGITIMATE,
                FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE,
                List.of("ANALYST_CONFIRMED_LEGITIMATE")
        );
    }

    @Test
    void schemaEquivalentContractRejectsMismatchedEvaluationPairs() {
        assertSchemaEquivalentRecordRejected(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.NEGATIVE_LEGITIMATE,
                List.of("ANALYST_CONFIRMED_FRAUD")
        );
        assertSchemaEquivalentRecordRejected(
                FraudFeedbackLabel.CONFIRMED_LEGITIMATE,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("ANALYST_CONFIRMED_LEGITIMATE")
        );
    }

    @Test
    void schemaEquivalentContractRejectsElevenDecisionReasonCodes() {
        assertSchemaEquivalentRecordRejected(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of(
                        "ANALYST_CONFIRMED_FRAUD",
                        "CUSTOMER_CONFIRMED_FRAUD",
                        "DOCUMENTATION_CONFIRMED_FRAUD",
                        "CHARGEBACK_SIGNAL",
                        "ACCOUNT_TAKEOVER_INDICATOR",
                        "ANALYST_CONFIRMED_FRAUD",
                        "CUSTOMER_CONFIRMED_FRAUD",
                        "DOCUMENTATION_CONFIRMED_FRAUD",
                        "CHARGEBACK_SIGNAL",
                        "ACCOUNT_TAKEOVER_INDICATOR",
                        "ANALYST_CONFIRMED_FRAUD"
                )
        );
    }

    @Test
    void generatedJsonlRecordLineDoesNotContainForbiddenFields() throws Exception {
        String recordLine = new FeedbackDatasetJsonlWriter()
                .writeJsonl(result(List.of(record())))
                .lines()
                .skip(1)
                .findFirst()
                .orElseThrow();

        assertThat(recordLine).doesNotContain(
                "transactionId",
                "feedbackId",
                "customerId",
                "correlationId",
                "createdBy",
                "notes",
                "rawNotes",
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
    void failedBuildEmitsMetadataOnly() {
        String jsonl = new FeedbackDatasetJsonlWriter().writeJsonl(failedResult());

        assertThat(jsonl.lines()).hasSize(1);
        assertThat(jsonl)
                .contains("\"type\":\"DATASET_METADATA\"")
                .contains("\"failureReason\":\"FEEDBACK_STORE_UNAVAILABLE\"")
                .doesNotContain("\"type\":\"DATASET_RECORD\"");
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
        return record(
                FraudFeedbackLabel.CONFIRMED_FRAUD,
                FeedbackEvaluationLabel.POSITIVE_FRAUD,
                List.of("ANALYST_CONFIRMED_FRAUD")
        );
    }

    private FeedbackDatasetRecord record(
            FraudFeedbackLabel feedbackLabel,
            FeedbackEvaluationLabel evaluationLabel,
            List<String> decisionReasonCodes
    ) {
        return new FeedbackDatasetRecord(
                FeedbackDatasetBuilder.DATASET_VERSION,
                FeedbackDatasetIdentifierHasher.evaluationRecordId("feedback-raw-1"),
                FeedbackDatasetIdentifierHasher.transactionReference("txn-raw-1"),
                feedbackLabel,
                evaluationLabel,
                decisionReasonCodes,
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

    private void assertSchemaEquivalentRecordAccepted(
            FraudFeedbackLabel feedbackLabel,
            FeedbackEvaluationLabel evaluationLabel,
            List<String> decisionReasonCodes
    ) {
        FeedbackDatasetRecord accepted = record(feedbackLabel, evaluationLabel, decisionReasonCodes);

        assertThat(accepted.feedbackLabel()).isEqualTo(feedbackLabel);
        assertThat(accepted.evaluationLabel()).isEqualTo(evaluationLabel);
        assertThat(accepted.decisionReasonCodes()).hasSizeLessThanOrEqualTo(10);
    }

    private void assertSchemaEquivalentRecordRejected(
            FraudFeedbackLabel feedbackLabel,
            FeedbackEvaluationLabel evaluationLabel,
            List<String> decisionReasonCodes
    ) {
        try {
            record(feedbackLabel, evaluationLabel, decisionReasonCodes);
        } catch (IllegalArgumentException exception) {
            return;
        }
        throw new AssertionError("schema-equivalent record contract accepted invalid dataset record");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (current.endsWith("alert-service")) {
            return current.getParent();
        }
        return current;
    }
}
