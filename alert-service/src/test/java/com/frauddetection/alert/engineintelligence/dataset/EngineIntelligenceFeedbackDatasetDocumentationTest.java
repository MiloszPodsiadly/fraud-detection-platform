package com.frauddetection.alert.engineintelligence.dataset;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFeedbackDatasetDocumentationTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path DOC = ROOT.resolve("docs/architecture/engine_intelligence_feedback_dataset_export.md");
    private static final Path RECORD_SCHEMA = ROOT.resolve(
            "docs/schemas/engine_intelligence_feedback_dataset_record.schema.json");

    @Test
    void docsMentionSingleTimeBasis() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("single declared time basis")
                .contains("FEEDBACK_SUBMITTED_AT")
                .contains("fromInclusive <= submittedAt <= toInclusive");
    }

    @Test
    void docsStateInternalOnlyBoundaryAndFutureAuditScope() throws IOException {
        String doc = normalizedDoc();

        assertThat(doc)
                .contains("internal service foundation")
                .contains("no public API")
                .contains("sensitive-read audit")
                .contains("privacy review")
                .contains("retention policy");
    }

    @Test
    void docsStateSpringServiceIsNotApprovedExportSurface() throws IOException {
        String doc = normalizedDoc();

        assertThat(doc)
                .contains("internal service foundation only")
                .contains("not an approved export surface")
                .contains("presence of a Spring service")
                .contains("does not authorize public, operator-triggered, scheduled, CLI, or external export");
    }

    @Test
    void docsStateFutureExportNeedsSeparateScopedPrControls() throws IOException {
        String doc = normalizedDoc();

        assertThat(doc)
                .contains("separate scoped PR")
                .contains("authorization")
                .contains("sensitive-read audit")
                .contains("rate limits")
                .contains("privacy review")
                .contains("retention policy")
                .contains("access controls")
                .contains("operational monitoring")
                .contains("privacy-reviewed identifier strategy");
    }

    @Test
    void docsMentionSupportedProjectionContractVersion() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("accepts supported projection")
                .contains("contract versions `1` and `2`")
                .contains("fails closed for unsupported projection versions")
                .contains("Unsupported projection")
                .contains("does not mean no fraud or low risk");
    }

    @Test
    void docsMentionFailedExportConsumerAbortSemantics() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("failureReason != null")
                .contains("hard export")
                .contains("failure")
                .contains("not a successful empty dataset")
                .contains("Consumers must abort processing")
                .contains("must not")
                .contains("count the file as an evaluation input")
                .contains("Failed exports must not contain dataset records");
    }

    @Test
    void docsMentionPseudonymousNotAnonymous() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("pseudonymous references")
                .contains("not anonymized");
    }

    @Test
    void docsMentionNotCryptographicPrivacyBoundary() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("cryptographic privacy boundary")
                .contains("not a");
    }

    @Test
    void docsMentionDeterministicReferencesAreLinkable() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("deterministic")
                .contains("linkable across exports")
                .contains("dictionary matching");
    }

    @Test
    void docsMentionFutureExternalExportNeedsPrivacyReviewedIdentifierStrategy() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("Future public/operator/external export")
                .contains("privacy-reviewed identifier strategy")
                .contains("keyed HMAC")
                .contains("tokenization")
                .contains("rotation");
    }

    @Test
    void docsMentionBoundedSampleNotCompleteDataset() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc).contains("bounded sample export, not an exhaustive dataset export");
    }

    @Test
    void docsMentionRecordsReturnedLessThanMaxDoesNotImplyCompleteWindow() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("recordsReturned < maxRecords")
                .contains("imply the full time window is exhausted");
    }

    @Test
    void docsMentionBoundedPerRowLookupSemantics() throws IOException {
        String doc = normalizedDoc();

        assertThat(doc)
                .contains("bounded per-row alert/projection lookups")
                .contains("maxRecords + 1 raw feedback window")
                .contains("acceptable for internal foundation scope")
                .contains("future public/operator/scheduled export")
                .contains("performance review")
                .contains("batch lookup");
    }

    @Test
    void docsDefineCompleteGroupedReasonCodeAndIndependentSignalLimits() throws IOException {
        assertThat(normalizedDoc())
                .contains("up to 20 occurrences")
                .contains("reasonCodesByEngine")
                .contains("does not truncate or deduplicate")
                .contains("global limit of five")
                .contains("feedback_dataset_record.schema.json")
                .contains("feedback-dataset-v3");

        JsonNode schema = JsonMapper.builder().findAndAddModules().build().readTree(RECORD_SCHEMA.toFile());
        assertThat(schema.at("/properties/reasonCodes/maxItems").intValue()).isEqualTo(20);
        assertThat(schema.at("/properties/reasonCodesByEngine/additionalProperties/maxItems").intValue()).isEqualTo(5);
        assertThat(schema.at("/properties/diagnosticSignals/maxItems").intValue()).isEqualTo(5);
        assertThat(schema.at("/allOf/0/if/properties/projectionStatus/const").textValue()).isEqualTo("AVAILABLE");
        assertThat(schema.at("/allOf/0/then/properties/reasonCodesByEngine/minProperties").intValue()).isEqualTo(2);
        assertThat(schema.at("/allOf/0/then/properties/reasonCodesByEngine/required").toString())
                .isEqualTo("[\"rules.primary\",\"ml.python.primary\"]");
        assertThat(schema.at("/allOf/1/if/properties/projectionStatus/const").textValue()).isEqualTo("MISSING");
        assertThat(schema.at("/allOf/1/then/properties/reasonCodes/maxItems").intValue()).isZero();
        assertThat(schema.at("/allOf/1/then/properties/reasonCodesByEngine/maxProperties").intValue()).isZero();
        assertThat(schema.at("/allOf/1/then/properties/diagnosticSignals/maxItems").intValue()).isZero();
    }

    @Test
    void docsKeepEngineIntelligenceFeedbackSeparateFromExactModelEvidence() throws IOException {
        assertThat(normalizedDoc())
                .contains("analyst usefulness/accuracy feedback")
                .contains("not the source of exact model prediction evidence")
                .contains("FraudFeedbackRecord")
                .contains("MlPredictionEvidenceProjection")
                .contains("must not use EngineIntelligenceFeedbackDatasetExport")
                .contains("current Engine Intelligence projection selected by transaction ID")
                .contains("latest/current diagnostic projection")
                .contains("was future scope for that historical delivery")
                .contains("current offline suite is now implemented")
                .contains("feedback-dataset-v3 exact-occurrence bounded context");
    }

    private static String normalizedDoc() throws IOException {
        return Files.readString(DOC)
                .replace("`", "")
                .replaceAll("\\s+", " ");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (current.endsWith("alert-service")) {
            return current.getParent();
        }
        return current;
    }
}
