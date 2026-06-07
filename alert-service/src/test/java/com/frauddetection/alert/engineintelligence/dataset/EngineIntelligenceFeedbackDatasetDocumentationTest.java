package com.frauddetection.alert.engineintelligence.dataset;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceFeedbackDatasetDocumentationTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path DOC = ROOT.resolve("docs/architecture/engine_intelligence_feedback_dataset_export.md");

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
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("internal service foundation")
                .contains("no public API")
                .contains("sensitive-read audit")
                .contains("privacy review")
                .contains("retention policy");
    }

    @Test
    void docsMentionSupportedProjectionContractVersion() throws IOException {
        String doc = Files.readString(DOC);

        assertThat(doc)
                .contains("FDP-102 exports only supported projection")
                .contains("contract version `1`")
                .contains("unsupported projection versions fail closed")
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

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (current.endsWith("alert-service")) {
            return current.getParent();
        }
        return current;
    }
}
