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

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (current.endsWith("alert-service")) {
            return current.getParent();
        }
        return current;
    }
}
