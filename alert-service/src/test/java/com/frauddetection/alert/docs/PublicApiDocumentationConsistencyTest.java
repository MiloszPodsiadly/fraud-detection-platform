package com.frauddetection.alert.docs;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiDocumentationConsistencyTest {

    @Test
    void publicStatusDocsCoverEverySubmitDecisionOperationStatus() throws Exception {
        String truthTable = Files.readString(Path.of("../docs/api/status-truth-table.md"));
        String semantics = Files.readString(Path.of("../docs/api/public-api-semantics.md"));
        String openApi = Files.readString(Path.of("../docs/openapi/alert-service.openapi.yaml"));

        for (SubmitDecisionOperationStatus status : SubmitDecisionOperationStatus.values()) {
            assertThat(truthTable).contains(status.name());
            assertThat(openApi).contains(status.name());
        }
        assertThat(semantics)
                .contains("Local evidence confirmation is not external finality")
                .contains("Checkpoint renewal preserves lease ownership only")
                .contains("Signed release or provenance artifacts are release controls");
    }

    @Test
    void publicDocsRejectKnownFalseEquivalences() throws Exception {
        String combined = Files.readString(Path.of("../docs/api/public-api-semantics.md"))
                + "\n" + Files.readString(Path.of("../docs/api/status-truth-table.md"))
                + "\n" + Files.readString(Path.of("../docs/api/openapi-safety-audit.md"));

        assertThat(combined)
                .contains("not external finality")
                .contains("not proof of business progress")
                .contains("not success")
                .contains("not distributed exactly-once")
                .contains("not proof of business correctness");

        assertThat(combined.toLowerCase())
                .doesNotContain("local committed == external finality")
                .doesNotContain("recovery required == success")
                .doesNotContain("signed release == business correctness");
    }
}
