package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AlertEvidenceSnapshotDocumentationContractTest {

    @Test
    void docsStateNonClaimsBoundednessAndPointInTimeSemantics() throws IOException {
        String docs = Files.readString(docsPath());

        assertThat(docs)
                .contains("point-in-time projection")
                .contains("not confirmed fraud")
                .contains("not analyst decision")
                .contains("not final outcome")
                .contains("does not provide write-once immutable storage guarantees")
                .contains("not external-authority verification")
                .contains("does not mutate case lifecycle")
                .contains("Default max item count is 50")
                .contains("Projection configuration accepts max item counts from 2 through 100")
                .contains("AlertDocument hard persistence cap is 100")
                .contains("No silent truncation is allowed")
                .contains("If alert evidence snapshot projection fails, alert creation must not produce fake AVAILABLE evidence")
                .contains("AlertDocument does not silently truncate")
                .contains("Projection errors are not normal degraded success")
                .contains("fraud.alert.evidence_snapshot.projection.error")
                .contains("operational incident signal")
                .contains("Error diagnostics must not contain raw exception messages")
                .contains("Error diagnostics must not contain stack traces")
                .contains("Error diagnostics must not contain raw event payloads")
                .contains("Error diagnostics must not create AVAILABLE evidence")
                .contains("FDP-59 does not change case lifecycle ordering")
                .contains("ERROR_PROJECTION_FAILED")
                .contains("ERROR_PROJECTED")
                .contains("LEGACY_PROJECTED");

        assertThat(docs.toLowerCase())
                .doesNotContain("legal proof")
                .doesNotContain("confirmed fraud evidence")
                .doesNotContain("final verdict")
                .doesNotContain("legacy_not_projected");
    }

    private Path docsPath() {
        Path moduleRelative = Path.of("..", "docs", "product", "alert_evidence_snapshot.md");
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("docs", "product", "alert_evidence_snapshot.md");
    }
}
