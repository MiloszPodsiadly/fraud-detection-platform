package com.frauddetection.alert.docs;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiDocumentationConsistencyTest {

    @Test
    void publicStatusDocsCoverEverySubmitDecisionOperationStatus() throws Exception {
        String truthTable = Files.readString(Path.of("../docs/api/status-truth-table.md"));
        String semantics = Files.readString(Path.of("../docs/api/public-api-semantics.md"));
        String openApi = Files.readString(Path.of("../docs/openapi/alert-service.openapi.yaml"));

        for (SubmitDecisionOperationStatus status : SubmitDecisionOperationStatus.values()) {
            assertThat(truthTable).contains(status.name());
            assertThat(semantics).contains(status.name());
            assertThat(openApi).contains(status.name());
        }
        assertThat(semantics)
                .contains("Local evidence confirmation is not external finality")
                .contains("Checkpoint renewal preserves lease ownership only")
                .contains("Lease renewal preserves the current worker's ownership window only")
                .contains("Signed release or provenance artifacts are release controls");
    }

    @Test
    void publicResponseFieldDocsCoverCurrentDtoFields() throws Exception {
        String semantics = Files.readString(Path.of("../docs/api/public-api-semantics.md"));

        List<String> submitDecisionFields = List.of(
                "alertId",
                "decision",
                "resultingStatus",
                "decisionEventId",
                "decidedAt",
                "operation_status"
        );
        for (String field : submitDecisionFields) {
            assertThat(semantics)
                    .as(SubmitAnalystDecisionResponse.class.getSimpleName() + " field must be documented: " + field)
                    .contains("`" + field + "`");
        }

        List<String> fraudCaseFields = List.of(
                "operation_status",
                "command_id",
                "idempotency_key_hash",
                "case_id",
                "current_case_snapshot",
                "updated_case",
                "recovery_required_reason"
        );
        for (String field : fraudCaseFields) {
            assertThat(semantics)
                    .as(UpdateFraudCaseResponse.class.getSimpleName() + " field must be documented: " + field)
                    .contains("`" + field + "`");
        }
    }

    @Test
    void publicDocsRejectKnownFalseEquivalences() throws Exception {
        String combined = Files.readString(Path.of("../docs/api/public-api-semantics.md"))
                + "\n" + Files.readString(Path.of("../docs/api/status-truth-table.md"))
                + "\n" + Files.readString(Path.of("../docs/api/openapi-safety-audit.md"));

        assertThat(combined)
                .contains("not external finality")
                .contains("not proof of business progress")
                .contains("Recovery required is not success")
                .contains("Pending external evidence is not confirmed")
                .contains("Local evidence is not external finality")
                .contains("not success")
                .contains("not distributed exactly-once")
                .contains("not proof of business correctness");

        assertThat(combined.toLowerCase())
                .doesNotContain("local committed == external finality")
                .doesNotContain("recovery required == success")
                .doesNotContain("signed release == business correctness");
    }
}
