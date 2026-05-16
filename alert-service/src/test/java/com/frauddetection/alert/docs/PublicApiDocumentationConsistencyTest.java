package com.frauddetection.alert.docs;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiDocumentationConsistencyTest {

    private static final Pattern UPPERCASE_TOKEN = Pattern.compile("\\b[A-Z][A-Z0-9_]{3,}\\b");
    private static final Set<String> NON_STATUS_TOKENS = Set.of(
            "HTTP",
            "WORM",
            "ACID",
            "FRAUD_CASE_VALIDATION_FAILED",
            "MISSING_IDEMPOTENCY_KEY"
    );

    @Test
    void publicStatusDocsCoverEverySubmitDecisionOperationStatus() throws Exception {
        Path docsRoot = DocumentationTestSupport.docsRoot();
        String truthTable = Files.readString(docsRoot.resolve("api/status_truth_table.md"));
        String semantics = Files.readString(docsRoot.resolve("api/public_api_semantics.md"));
        String openApi = Files.readString(docsRoot.resolve("openapi/alert_service.openapi.yaml"));

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

        Set<String> currentStatuses = Arrays.stream(SubmitDecisionOperationStatus.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        Set<String> documentedStatusTokens = UPPERCASE_TOKEN.matcher(semantics)
                .results()
                .map(match -> match.group())
                .filter(token -> !NON_STATUS_TOKENS.contains(token))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertThat(documentedStatusTokens)
                .as("Public API semantics must not document removed status names unless explicitly historical")
                .isSubsetOf(currentStatuses);
    }

    @Test
    void publicResponseFieldDocsCoverCurrentDtoFields() throws Exception {
        String semantics = Files.readString(DocumentationTestSupport.docsRoot().resolve("api/public_api_semantics.md"));

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
        Path docsRoot = DocumentationTestSupport.docsRoot();
        String combined = Files.readString(docsRoot.resolve("api/public_api_semantics.md"))
                + "\n" + Files.readString(docsRoot.resolve("api/status_truth_table.md"))
                + "\n" + Files.readString(docsRoot.resolve("api/openapi_safety_audit.md"));

        assertThat(combined)
                .contains("not external finality")
                .contains("not proof of business progress")
                .contains("Recovery required is not success")
                .contains("Pending external evidence is not confirmed")
                .contains("Local evidence is not external finality")
                .contains("not success")
                .contains("not distributed exactly-once")
                .contains("not proof of business correctness")
                .contains("FINALIZED_VISIBLE is a compatibility-visible status")
                .contains("FINALIZED_VISIBLE is not external confirmation");

        assertThat(combined.toLowerCase())
                .doesNotContain("local committed == external finality")
                .doesNotContain("recovery required == success")
                .doesNotContain("signed release == business correctness");
    }
}
