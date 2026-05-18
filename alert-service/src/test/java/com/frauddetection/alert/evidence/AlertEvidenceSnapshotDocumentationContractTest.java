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
                .contains("No silent truncation is allowed");

        assertThat(docs.toLowerCase())
                .doesNotContain("legal proof")
                .doesNotContain("confirmed fraud evidence")
                .doesNotContain("final verdict");
    }

    private Path docsPath() {
        Path moduleRelative = Path.of("..", "docs", "product", "alert_evidence_snapshot.md");
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("docs", "product", "alert_evidence_snapshot.md");
    }
}
