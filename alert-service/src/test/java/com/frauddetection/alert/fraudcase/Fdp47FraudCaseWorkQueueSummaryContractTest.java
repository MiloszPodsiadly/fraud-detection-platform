package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp47FraudCaseWorkQueueSummaryContractTest {

    @Test
    void docsAndOpenApiMustDescribeSummaryAsGlobalAndNotSnapshotConsistent() throws Exception {
        String docs = Files.readString(projectRoot().resolve("docs/fdp/fdp_47_analyst_console_ux_and_summary.md"));
        String openApi = Files.readString(projectRoot().resolve("docs/openapi/alert_service.openapi.yaml"));
        String combined = docs + "\n" + openApi;

        assertThat(combined)
                .contains("/api/v1/fraud-cases/work-queue/summary")
                .contains("point-in-time global count")
                .contains("GLOBAL_FRAUD_CASES")
                .contains("not filter-scoped")
                .contains("not cursor-scoped")
                .contains("not used for pagination metadata")
                .contains("not snapshot-consistent")
                .contains("snapshotConsistentWithWorkQueue")
                .contains("FRAUD_CASE_WORK_QUEUE_SUMMARY")
                .contains("post-auth observations")
                .contains("401/403 outcomes are covered by security tests");
        assertThat(combined.toLowerCase())
                .doesNotContain("snapshot-consistent global")
                .doesNotContain("filter-scoped total")
                .doesNotContain("cursor-scoped total")
                .doesNotContain("summary drives pagination")
                .doesNotContain("total queue cases");
    }

    @Test
    void openApiMustExposeOnlyCurrentV1SummaryEndpoint() throws Exception {
        String openApi = Files.readString(projectRoot().resolve("docs/openapi/alert_service.openapi.yaml"));
        String summaryEndpoint = section(openApi, "  /api/v1/fraud-cases/work-queue/summary:", "  /api/v1/fraud-cases/{caseId}:");

        assertThat(summaryEndpoint)
                .contains("$ref: \"#/components/schemas/FraudCaseWorkQueueSummaryResponse\"")
                .contains("No legacy alias is supported")
                .doesNotContain("/api/fraud-cases/work-queue/summary");
    }

    private String section(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        return source.substring(startIndex, endIndex);
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
