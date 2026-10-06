package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackDatasetSchemaContractTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path SCHEMA = ROOT.resolve("docs/schemas/feedback_dataset_record.schema.json");
    private static final Path MODEL_IDENTITY_CASES = ROOT.resolve(
            "contract-fixtures/public-api/ml-model-identity-cases.json"
    );
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
                        "\"feedbackCreatedAt\"",
                        "\"rulesEvidenceStatus\"",
                        "\"rulesRiskLevel\"",
                        "\"mlPredictionEvidenceStatus\"",
                        "\"mlPredictionScore\"",
                        "\"mlPredictionRiskLevel\"",
                        "\"mlPredictionExecutedAt\"",
                        "\"mlModelName\"",
                        "\"mlModelVersion\"",
                        "\"mlFeatureContractVersion\""
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
    void schemaDocumentsAtomicMlModelIdentityConstraint() throws Exception {
        String schema = Files.readString(SCHEMA);

        assertThat(schema)
                .contains("\"oneOf\"")
                .contains("\"mlModelName\"")
                .contains("\"mlModelVersion\"")
                .contains("\"mlFeatureContractVersion\"")
                .contains("\"maxLength\": 64")
                .contains("\"maxLength\": 96")
                .contains("\"pattern\": \"^[A-Za-z0-9._-]+$\"");
    }

    @Test
    void jsonSchemaAcceptsZeroOrCompleteMlIdentityAndRejectsEveryPartialState() throws Exception {
        assertSchemaInvalid(datasetRecordLine(null, null, null, false));
        assertSchemaValid(datasetRecordLine(null, null, null, true));
        assertSchemaValid(datasetRecordLine("model", "v1", "feature-contract-v1", true));

        String[][] partialStates = {
                {"model", null, null},
                {null, "v1", null},
                {null, null, "feature-contract-v1"},
                {"model", "v1", null},
                {"model", null, "feature-contract-v1"},
                {null, "v1", "feature-contract-v1"}
        };
        for (String[] state : partialStates) {
            assertSchemaInvalid(datasetRecordLine(state[0], state[1], state[2], true));
        }
    }

    @Test
    void jsonSchemaRejectsPartialOrContradictoryPredictionEvidence() throws Exception {
        Map<String, Object> availableWithoutScore = datasetRecordLine(
                "model",
                "v1",
                "feature-contract-v1",
                true
        );
        nestedRecord(availableWithoutScore).put("mlPredictionScore", null);
        assertSchemaInvalid(availableWithoutScore);

        Map<String, Object> absentWithZeroScore = datasetRecordLine(null, null, null, true);
        nestedRecord(absentWithZeroScore).put("mlPredictionScore", 0.0);
        assertSchemaInvalid(absentWithZeroScore);

        Map<String, Object> unavailableStatus = datasetRecordLine(null, null, null, true);
        nestedRecord(unavailableStatus).put("mlPredictionEvidenceStatus", "MISSING_UNEXPECTEDLY");
        assertSchemaInvalid(unavailableStatus);
    }

    @Test
    void jsonSchemaRejectsMissingOrContradictoryRulesEvidence() throws Exception {
        Map<String, Object> missingStatus = datasetRecordLine(null, null, null, true);
        nestedRecord(missingStatus).remove("rulesEvidenceStatus");
        assertSchemaInvalid(missingStatus);

        Map<String, Object> availableWithoutRisk = datasetRecordLine(null, null, null, true);
        nestedRecord(availableWithoutRisk).put("rulesEvidenceStatus", "AVAILABLE");
        assertSchemaInvalid(availableWithoutRisk);

        Map<String, Object> unavailableWithRisk = datasetRecordLine(null, null, null, true);
        nestedRecord(unavailableWithRisk).put("rulesRiskLevel", "LOW");
        assertSchemaInvalid(unavailableWithRisk);
    }

    @Test
    void jsonSchemaRejectsNoncanonicalTimestampsScoresAndEnums() throws Exception {
        Map<String, Object> offsetTimestamp = datasetRecordLine(null, null, null, true);
        nestedRecord(offsetTimestamp).put("feedbackCreatedAt", "2026-06-01T02:00:00+02:00");
        assertSchemaInvalid(offsetTimestamp);

        Map<String, Object> excessiveScoreScale = datasetRecordLine(null, null, null, true);
        nestedRecord(excessiveScoreScale).put("fraudScore", 0.12345);
        assertSchemaInvalid(excessiveScoreScale);

        Map<String, Object> staleEnumAliases = datasetRecordLine(null, null, null, true);
        nestedRecord(staleEnumAliases).put("agreementStatus", "ENGINES_AGREE");
        nestedRecord(staleEnumAliases).put("riskMismatchStatus", "NONE");
        nestedRecord(staleEnumAliases).put("scoreDeltaBucket", "SMALL_DELTA");
        nestedRecord(staleEnumAliases).put("analystRecommendationStatus", "GENERATED");
        nestedRecord(staleEnumAliases).put("analystRecommendation", "REVIEW_TRANSACTION");
        assertSchemaInvalid(staleEnumAliases);
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonSchemaBoundsDatasetPopulationCounters() throws Exception {
        String metadataLine = new FeedbackDatasetJsonlWriter()
                .writeJsonl(result(List.of(record())))
                .lines()
                .findFirst()
                .orElseThrow();
        Map<String, Object> metadata = objectMapper.readValue(metadataLine, Map.class);

        metadata.put("rawRowsRead", 1002);
        assertSchemaInvalid(metadata);

        metadata.put("rawRowsRead", 1);
        metadata.put("excludedUnresolvedCount", 1001);
        assertSchemaInvalid(metadata);
    }

    @Test
    void jsonSchemaUsesSharedCanonicalMlIdentitySyntaxCases() throws Exception {
        JsonNode fixture = objectMapper.readTree(MODEL_IDENTITY_CASES.toFile());
        JsonNode canonical = fixture.get("canonicalIdentity");

        for (JsonNode identityCase : fixture.get("cases")) {
            String field = identityCase.get("field").asString();
            String modelName = canonical.get("modelName").asString();
            String modelVersion = canonical.get("modelVersion").asString();
            String featureContractVersion = canonical.get("featureContractVersion").asString();
            if ("modelName".equals(field)) {
                modelName = identityCase.get("value").asString();
            } else if ("modelVersion".equals(field)) {
                modelVersion = identityCase.get("value").asString();
            } else if ("featureContractVersion".equals(field)) {
                featureContractVersion = identityCase.get("value").asString();
            }

            boolean accepted = validateSchema(datasetRecordLine(
                    modelName,
                    modelVersion,
                    featureContractVersion,
                    true
            )).isEmpty();
            assertThat(accepted)
                    .as(identityCase.get("caseId").asString())
                    .isEqualTo(identityCase.get("validSyntax").booleanValue());
        }
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
        assertThat(record.get("rulesEvidenceStatus").asString()).isEqualTo("UNAVAILABLE");
        assertThat(record.get("rulesRiskLevel").isNull()).isTrue();
        assertThat(record.get("mlPredictionEvidenceStatus").asString()).isEqualTo("LEGITIMATELY_ABSENT");
        assertThat(record.get("mlPredictionScore").isNull()).isTrue();
        assertThat(record.get("mlPredictionRiskLevel").isNull()).isTrue();
        assertThat(record.get("mlPredictionExecutedAt").isNull()).isTrue();
        assertThat(record.has("mlModelName")).isTrue();
        assertThat(record.has("mlModelVersion")).isTrue();
        assertThat(record.has("mlFeatureContractVersion")).isTrue();
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
                FeedbackDatasetRulesEvidenceStatus.UNAVAILABLE,
                null,
                FeedbackDatasetMlPredictionEvidenceStatus.LEGITIMATELY_ABSENT,
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

    private void assertSchemaValid(Map<String, Object> payload) throws Exception {
        assertThat(validateSchema(payload)).isEmpty();
    }

    private void assertSchemaInvalid(Map<String, Object> payload) throws Exception {
        assertThat(validateSchema(payload)).isNotEmpty();
    }

    private List<Error> validateSchema(Map<String, Object> payload) throws Exception {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(SCHEMA));
        return schema.validate(objectMapper.writeValueAsString(payload), InputFormat.JSON);
    }

    private Map<String, Object> datasetRecordLine(
            String modelName,
            String modelVersion,
            String featureContractVersion,
            boolean includeIdentityFields
    ) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("datasetVersion", FeedbackDatasetBuilder.DATASET_VERSION);
        record.put("evaluationRecordId", "eval_11111111111111111111111111111111");
        record.put("transactionReference", "txnref_22222222222222222222222222222222");
        record.put("feedbackLabel", "CONFIRMED_FRAUD");
        record.put("evaluationLabel", "POSITIVE_FRAUD");
        record.put("decisionReasonCodes", List.of("ANALYST_CONFIRMED_FRAUD"));
        record.put("feedbackCreatedAt", "2026-06-01T00:00:00Z");
        record.put("rulesEvidenceStatus", "UNAVAILABLE");
        record.put("rulesRiskLevel", null);
        if (includeIdentityFields) {
            boolean available = modelName != null || modelVersion != null || featureContractVersion != null;
            record.put("mlPredictionEvidenceStatus", available ? "AVAILABLE" : "LEGITIMATELY_ABSENT");
            record.put("mlPredictionScore", available ? 0.8123 : null);
            record.put("mlPredictionRiskLevel", available ? "HIGH" : null);
            record.put("mlPredictionExecutedAt", available ? "2026-06-01T00:00:01Z" : null);
            record.put("mlModelName", modelName);
            record.put("mlModelVersion", modelVersion);
            record.put("mlFeatureContractVersion", featureContractVersion);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "DATASET_RECORD");
        envelope.put("record", record);
        return envelope;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedRecord(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("record");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (current.endsWith("alert-service")) {
            return current.getParent();
        }
        return current;
    }
}
