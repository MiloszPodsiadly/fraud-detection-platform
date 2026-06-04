package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceAnalystFeedbackDocsTest {

    @Test
    void docsStateFdp98ScopeAndNonGoals() throws Exception {
        String docs = Files.readString(repositoryRoot().resolve("docs/architecture/engine_intelligence_analyst_feedback.md"));

        assertThat(docs).contains(
                "Feedback is captured and audited, not executed.",
                "FDP-98 captures structured analyst feedback about engine intelligence usefulness and perceived accuracy.",
                "FDP-98 does not automatically change scoring, alert severity, fraud case status, approval, decline, blocking, payment authorization, rules, or model training.",
                "FDP-98 v1 has no free-text feedback.",
                "Feedback requires write/review authority, not read-only permission.",
                "Feedback is stored append-only.",
                "Successful feedback creates an audit entry.",
                "Idempotency prevents duplicate submissions.",
                "Feedback is not a training label, ground truth, or model correction.",
                "Future model evaluation/export requires separate governance review."
        );
        assertThat(docs).doesNotContain(
                "Feedback changes outcomes",
                "Feedback trains models automatically",
                "read-only permission can submit feedback"
        );
    }

    private Path repositoryRoot() throws IOException {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("alert-service"))
                    && Files.isDirectory(candidate.resolve("common-events"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found");
    }
}
