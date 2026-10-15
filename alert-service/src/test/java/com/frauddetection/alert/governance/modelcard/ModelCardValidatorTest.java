package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCardValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelCardValidator validator = new ModelCardValidator();

    @Test
    void acceptsExactArtifactCardWithoutGrantingDecisionAuthority() {
        ModelCard card = ModelCardTestFixtures.validUnavailableCard(objectMapper);

        assertThatNoException().isThrownBy(() -> validator.validate(card));
    }

    @Test
    void acceptsCanonicalAvailableEvaluationMetrics() throws Exception {
        ModelCard card = ModelCardTestFixtures.validAvailableCard(objectMapper);

        assertThatNoException().isThrownBy(() -> validator.validate(card));
    }

    @Test
    void matchesCanonicalPythonSixDecimalFloatRounding() {
        assertThat(ModelCardValidator.roundedMetricValue(1, 640)).isEqualByComparingTo("0.001563");
        assertThat(ModelCardValidator.roundedMetricValue(3, 640)).isEqualByComparingTo("0.004687");
    }

    @Test
    void rejectsUnknownSchemaAndPromotionState() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        ModelCard unknownSchema = copy(valid, "other-schema", valid.promotionState(), valid.evaluationEvidence());
        ModelCard promoted = copy(valid, valid.schemaVersion(), "APPROVED", valid.evaluationEvidence());

        assertThatThrownBy(() -> validator.validate(unknownSchema)).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validate(promoted)).isInstanceOf(ModelCardValidationException.class);
    }

    @Test
    void rejectsUnknownEvidenceFieldsAndDecisionAuthority() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        var evidence = valid.evaluationEvidence().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) evidence).put("rawRows", "forbidden");
        ModelCard unknownEvidence = copy(valid, valid.schemaVersion(), valid.promotionState(), evidence);
        ModelCard authority = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), null,
                new ModelCard.VerifiedUsageBoundary(
                        valid.verifiedUsageBoundary().allowedModes(),
                        valid.verifiedUsageBoundary().intendedUse(),
                        valid.verifiedUsageBoundary().notIntendedUse(),
                        valid.verifiedUsageBoundary().useLimitations(),
                        "PRIMARY", "NONE", "NONE", "NONE", "NONE"
                )
        );

        assertThatThrownBy(() -> validator.validate(unknownEvidence)).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validate(authority)).isInstanceOf(ModelCardValidationException.class);
    }

    @Test
    void rejectsSensitiveIdentityContent() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        ModelCard sensitive = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(),
                new ModelCard.Subject(
                        "ML_MODEL", "customerId_model", valid.subject().modelVersion(),
                        valid.subject().featureContractVersion(), valid.subject().modelArtifactSha256(), "COMPLETE"
                ),
                valid.promotionState(), valid.modelArtifactVerification(), valid.evaluationEvidence(), null,
                valid.verifiedUsageBoundary()
        );

        assertThatThrownBy(() -> validator.validate(sensitive)).isInstanceOf(ModelCardValidationException.class);
    }

    @Test
    void rejectsEvaluationWindowWithReversedBounds() {
        var window = objectMapper.createObjectNode()
                .put("timeBasis", "FEEDBACK_CREATED_AT")
                .put("fromInclusive", "2026-10-14T10:00:00Z")
                .put("toInclusive", "2026-10-13T10:00:00Z");

        assertThatThrownBy(() -> validator.validateWindow(window))
                .isInstanceOf(ModelCardValidationException.class)
                .hasMessageContaining("fromInclusive must not be later than");
    }

    @Test
    void rejectsMetricsThatDoNotReconcileWithConfusionCounts() throws Exception {
        assertAvailableCardRejected(summary -> {
            summary.withObject("/sourceDataset").put("rawRowsRead", 100).put("recordsReturned", 100);
            summary.withObject("/population")
                    .put("recordsConsidered", 100)
                    .put("recordsWithPredictionEvidence", 100)
                    .put("recordsEvaluated", 100);
            summary.withObject("/classBalance").put("positiveClassCount", 20).put("negativeClassCount", 80);
            ObjectNode prediction = prediction(summary);
            prediction.put("recordsEvaluated", 100)
                    .put("truePositive", 20)
                    .put("falsePositive", 80)
                    .put("trueNegative", 0)
                    .put("falseNegative", 0);
            metric(summary, "precision").put("value", 0.95);
            metric(summary, "recall").put("value", 1.0);
            metric(summary, "truePositiveRate").put("value", 1.0);
            metric(summary, "falsePositiveRate").put("value", 1.0);
            metric(summary, "falseNegativeRate").put("value", 0.0);
            rankedValue(summary, "recallAtK", "1").put("value", 0.05);
            rankedValue(summary, "recallAtK", "2").put("value", 0.05);
            ObjectNode disagreement = summary.withObject("/supportedMetrics/rulesVsMlDisagreement");
            disagreement.put("recordsWithExactMlEvidence", 100).put("recordsCompared", 100);
            disagreement.withObject("/categoryCounts").put("BOTH_HIGH", 100).put("BOTH_LOW", 0);
        });
        assertAvailableCardRejected(summary -> metric(summary, "recall").put("value", 0.5));
        assertAvailableCardRejected(summary -> metric(summary, "truePositiveRate").put("value", 0.5));
        assertAvailableCardRejected(summary -> metric(summary, "falsePositiveRate").put("value", 0.5));
        assertAvailableCardRejected(summary -> metric(summary, "falseNegativeRate").put("value", 0.5));
    }

    @Test
    void rejectsContradictoryOrArbitraryMetricAvailability() throws Exception {
        assertAvailableCardRejected(summary -> {
            ObjectNode precision = metric(summary, "precision");
            precision.put("available", false).put("reason", "NO_PREDICTED_POSITIVES").put("value", 0.5);
        });
        assertAvailableCardRejected(summary -> metric(summary, "precision").put("reason", "ARBITRARY_REASON"));
        assertAvailableCardRejected(summary -> {
            ObjectNode prediction = prediction(summary);
            prediction.put("truePositive", 0).put("falseNegative", 1);
            metric(summary, "precision").put("available", false)
                    .put("reason", "ARBITRARY_REASON")
                    .putNull("value");
        });
        assertAvailableCardRejected(summary -> {
            ObjectNode prediction = prediction(summary);
            prediction.put("truePositive", 0).put("falseNegative", 1);
            metric(summary, "precision").put("available", true).putNull("reason").put("value", 0.0);
        });
    }

    @Test
    void rejectsInconsistentRankedMetrics() throws Exception {
        assertAvailableCardRejected(summary -> rankedValue(summary, "precisionAtK", "2").put("value", 1.0));
        assertAvailableCardRejected(summary -> rankedValue(summary, "recallAtK", "2").put("value", 0.5));
        assertAvailableCardRejected(summary -> rankingRow(summary, "precisionAtK", "2").put("actualK", 1));
        assertAvailableCardRejected(summary -> rankingRow(summary, "recallAtK", "2").put("requestedK", 1));
        assertAvailableCardRejected(summary -> ((ObjectNode) prediction(summary).get("recallAtK")).remove("2"));
    }

    @Test
    void rejectsDisagreementCategoryMismatch() throws Exception {
        assertAvailableCardRejected(summary -> ((ObjectNode) summary.at(
                "/supportedMetrics/rulesVsMlDisagreement/categoryCounts"
        )).put("BOTH_LOW", 0));
    }

    @Test
    void derivesCanonicalWarningsFromValidatedEvidence() {
        assertWarningsAccepted(List.of(), warningSource(false, 0, 0), population(2, 2, 0, 0), balance(1, 1));
        assertWarningsAccepted(
                List.of("INSUFFICIENT_MODEL_LINEAGE_RECORDS", "MODEL_PREDICTION_SIGNAL_UNAVAILABLE"),
                warningSource(false, 0, 0), population(0, 0, 0, 0), balance(0, 0)
        );
        assertWarningsAccepted(
                List.of("SINGLE_CLASS_MODEL_LINEAGE_RECORDS"),
                warningSource(false, 0, 0), population(2, 2, 0, 0), balance(2, 0)
        );
        assertWarningsAccepted(
                List.of("MODEL_EVALUATION_PARTIAL_COVERAGE"),
                warningSource(false, 0, 0), population(3, 2, 0, 0), balance(1, 1)
        );
        assertWarningsAccepted(
                List.of("UNEXPECTED_ML_EVIDENCE_LOSS"),
                warningSource(false, 0, 0), population(2, 2, 1, 0), balance(1, 1)
        );
        assertWarningsAccepted(
                List.of("INVALID_ML_EVIDENCE_PRESENT"),
                warningSource(false, 0, 0), population(2, 2, 0, 1), balance(1, 1)
        );
        assertWarningsAccepted(
                List.of("SOURCE_DATASET_TRUNCATED"),
                warningSource(true, 0, 0), population(2, 2, 0, 0), balance(1, 1)
        );
        assertWarningsAccepted(
                List.of("SOURCE_DATASET_REQUIRED_FIELDS_MISSING"),
                warningSource(false, 1, 0), population(2, 2, 0, 0), balance(1, 1)
        );
        assertWarningsAccepted(
                List.of("SOURCE_DATASET_INVALID_ROWS_SKIPPED"),
                warningSource(false, 0, 1), population(2, 2, 0, 0), balance(1, 1)
        );
        assertWarningsAccepted(
                List.of(
                        "INSUFFICIENT_MODEL_LINEAGE_RECORDS",
                        "INVALID_ML_EVIDENCE_PRESENT",
                        "MODEL_EVALUATION_PARTIAL_COVERAGE",
                        "MODEL_PREDICTION_SIGNAL_UNAVAILABLE",
                        "SOURCE_DATASET_INVALID_ROWS_SKIPPED",
                        "SOURCE_DATASET_REQUIRED_FIELDS_MISSING",
                        "SOURCE_DATASET_TRUNCATED",
                        "UNEXPECTED_ML_EVIDENCE_LOSS"
                ),
                warningSource(true, 1, 1), population(3, 0, 1, 1), balance(0, 0)
        );
    }

    @Test
    void rejectsMissingExtraDuplicateOrOutOfOrderWarnings() {
        ObjectNode source = warningSource(false, 0, 0);
        Map<String, Integer> population = population(3, 2, 1, 0);
        Map<String, Integer> balance = balance(1, 1);
        assertThatThrownBy(() -> validator.validateExpectedWarnings(
                warnings("MODEL_EVALUATION_PARTIAL_COVERAGE"), source, population, balance
        )).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validateExpectedWarnings(
                warnings("MODEL_EVALUATION_PARTIAL_COVERAGE", "UNEXPECTED_ML_EVIDENCE_LOSS", "EXTRA"),
                source, population, balance
        )).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validateExpectedWarnings(
                warnings("UNEXPECTED_ML_EVIDENCE_LOSS", "MODEL_EVALUATION_PARTIAL_COVERAGE"),
                source, population, balance
        )).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validateExpectedWarnings(
                warnings("MODEL_EVALUATION_PARTIAL_COVERAGE", "MODEL_EVALUATION_PARTIAL_COVERAGE"),
                source, population, balance
        )).isInstanceOf(ModelCardValidationException.class);
    }

    @Test
    void rejectsNullUsageAuthoritiesWithoutEscapingValidation() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        assertBoundaryRejected(valid, null, "NONE", "NONE", "NONE", "NONE");
        assertBoundaryRejected(valid, "NONE", null, "NONE", "NONE", "NONE");
        assertBoundaryRejected(valid, "NONE", "NONE", null, "NONE", "NONE");
        assertBoundaryRejected(valid, "NONE", "NONE", "NONE", null, "NONE");
        assertBoundaryRejected(valid, "NONE", "NONE", "NONE", "NONE", null);
        ModelCard missing = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), valid.evaluationSummary(), null
        );
        assertThatThrownBy(() -> validator.validate(missing)).isInstanceOf(ModelCardValidationException.class);
    }

    @Test
    void rejectsUnsupportedUsageModeInvalidTimestampAndMalformedMetric() throws Exception {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        ModelCard invalidMode = withBoundary(valid, "NONE", "NONE", "NONE", "NONE", "NONE",
                List.of("ML"));
        ModelCard invalidTimestamp = new ModelCard(
                valid.schemaVersion(), valid.cardType(), "not-a-timestamp", valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), valid.evaluationSummary(),
                valid.verifiedUsageBoundary()
        );
        assertThatThrownBy(() -> validator.validate(invalidMode)).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validate(invalidTimestamp)).isInstanceOf(ModelCardValidationException.class);
        assertAvailableCardRejected(summary -> metric(summary, "precision").remove("value"));
    }

    @Test
    void rejectsMissingOrModifiedUsagePolicy() {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);

        assertThatThrownBy(() -> validator.validate(withUsagePolicy(
                valid, null, valid.verifiedUsageBoundary().notIntendedUse(),
                valid.verifiedUsageBoundary().useLimitations()
        ))).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validate(withUsagePolicy(
                valid, valid.verifiedUsageBoundary().intendedUse(), List.of("PRIMARY_PRODUCTION_FRAUD_DECISIONING"),
                valid.verifiedUsageBoundary().useLimitations()
        ))).isInstanceOf(ModelCardValidationException.class);
        assertThatThrownBy(() -> validator.validate(withUsagePolicy(
                valid, valid.verifiedUsageBoundary().intendedUse(),
                valid.verifiedUsageBoundary().notIntendedUse(), List.of()
        ))).isInstanceOf(ModelCardValidationException.class);
    }

    private void assertAvailableCardRejected(Consumer<ObjectNode> mutation) throws Exception {
        ModelCard valid = ModelCardTestFixtures.validAvailableCard(objectMapper);
        ObjectNode summary = (ObjectNode) valid.evaluationSummary().deepCopy();
        mutation.accept(summary);
        ModelCard mutated = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), summary, valid.verifiedUsageBoundary()
        );

        assertThatThrownBy(() -> validator.validate(mutated)).isInstanceOf(ModelCardValidationException.class);
    }

    private ObjectNode prediction(ObjectNode summary) {
        return (ObjectNode) summary.at("/supportedMetrics/mlPredictionMetrics");
    }

    private ObjectNode metric(ObjectNode summary, String name) {
        return (ObjectNode) prediction(summary).get(name);
    }

    private ObjectNode rankingRow(ObjectNode summary, String metricName, String key) {
        return (ObjectNode) prediction(summary).get(metricName).get(key);
    }

    private ObjectNode rankedValue(ObjectNode summary, String metricName, String key) {
        return (ObjectNode) rankingRow(summary, metricName, key).get("value");
    }

    private void assertWarningsAccepted(
            List<String> expected,
            ObjectNode source,
            Map<String, Integer> population,
            Map<String, Integer> balance
    ) {
        assertThatNoException().isThrownBy(() -> validator.validateExpectedWarnings(
                warnings(expected.toArray(String[]::new)), source, population, balance
        ));
    }

    private ObjectNode warningSource(boolean truncated, int missingRequired, int invalidRows) {
        return objectMapper.createObjectNode()
                .put("truncated", truncated)
                .put("skippedMissingRequiredFieldCount", missingRequired)
                .put("skippedInvalidSourceRecordCount", invalidRows);
    }

    private Map<String, Integer> population(int considered, int evaluated, int unexpectedMissing, int invalid) {
        return Map.of(
                "recordsConsidered", considered,
                "recordsEvaluated", evaluated,
                "recordsExcludedUnexpectedMissingPredictionEvidence", unexpectedMissing,
                "recordsExcludedInvalidPredictionEvidence", invalid
        );
    }

    private Map<String, Integer> balance(int positive, int negative) {
        return Map.of("positiveClassCount", positive, "negativeClassCount", negative);
    }

    private tools.jackson.databind.node.ArrayNode warnings(String... values) {
        var warnings = objectMapper.createArrayNode();
        for (String value : values) {
            warnings.add(value);
        }
        return warnings;
    }

    private void assertBoundaryRejected(
            ModelCard valid,
            String runtime,
            String promotion,
            String threshold,
            String payment,
            String workflow
    ) {
        assertThatThrownBy(() -> validator.validate(withBoundary(
                valid, runtime, promotion, threshold, payment, workflow,
                List.of("COMPARE", "OFFLINE_EVALUATION", "SHADOW")
        ))).isInstanceOf(ModelCardValidationException.class);
    }

    private ModelCard withBoundary(
            ModelCard valid,
            String runtime,
            String promotion,
            String threshold,
            String payment,
            String workflow,
            List<String> modes
    ) {
        return new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), valid.evaluationSummary(),
                new ModelCard.VerifiedUsageBoundary(
                        modes,
                        valid.verifiedUsageBoundary().intendedUse(),
                        valid.verifiedUsageBoundary().notIntendedUse(),
                        valid.verifiedUsageBoundary().useLimitations(),
                        runtime, promotion, threshold, payment, workflow
                )
        );
    }

    private ModelCard withUsagePolicy(
            ModelCard valid,
            List<String> intendedUse,
            List<String> notIntendedUse,
            List<String> useLimitations
    ) {
        ModelCard.VerifiedUsageBoundary boundary = valid.verifiedUsageBoundary();
        return new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), valid.evaluationSummary(),
                new ModelCard.VerifiedUsageBoundary(
                        boundary.allowedModes(), intendedUse, notIntendedUse, useLimitations,
                        boundary.runtimeDecisionAuthority(), boundary.promotionAuthority(),
                        boundary.thresholdChangeAuthority(), boundary.paymentAuthorizationAuthority(),
                        boundary.workflowAuthority()
                )
        );
    }

    private ModelCard copy(
            ModelCard source,
            String schema,
            String promotionState,
            tools.jackson.databind.JsonNode evidence
    ) {
        return new ModelCard(
                schema, source.cardType(), source.generatedAt(), source.subject(), promotionState,
                source.modelArtifactVerification(), evidence, source.evaluationSummary(), source.verifiedUsageBoundary()
        );
    }
}
