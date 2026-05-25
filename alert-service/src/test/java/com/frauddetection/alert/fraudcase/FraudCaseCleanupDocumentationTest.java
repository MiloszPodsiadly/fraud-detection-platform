package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseCleanupDocumentationTest {

    @Test
    void currentDocumentationStatesTheReducedSurfaceAndRemovedLifecycleSubsystem() throws IOException {
        String current = read(List.of(
                "docs/api/fraud_case_api.md",
                "docs/product/fraud_case_management.md",
                "docs/architecture/fraud_case_management_architecture.md",
                "docs/runbooks/fraud_case_operations.md",
                "docs/product/investigation_evidence_platform_cleanup_inventory.md"
        ));

        assertThat(current)
                .contains("FDP-81")
                .contains("PATCH")
                .contains("RegulatedMutationCoordinator")
                .contains("removes the standalone")
                .contains("lifecycle idempotency metric");
    }

    @Test
    void retiredDashboardIsMarkedHistoricalAndNotCurrentOperations() throws IOException {
        String dashboard = Files.readString(resolve("docs/observability/fraud_case_lifecycle_idempotency_dashboard.md"));

        assertThat(dashboard)
                .contains("historical FDP-44 artifact; superseded by FDP-81")
                .contains("emitter")
                .contains("not a current operational source");
    }

    @Test
    void fdp50ReleaseNoteSeparatesHistoricalGoneResponseFromCurrentFdp81Behavior() throws IOException {
        String releaseNote = Files.readString(resolve("docs/release/fdp_50_legacy_api_removal.md"));

        assertThat(releaseNote)
                .contains("Historical Behavior In FDP-50")
                .contains("returned `410 Gone` with `code:LEGACY_FRAUD_CASE_ROUTE_REMOVED`")
                .contains("FDP-81 removes the unversioned compatibility handler")
                .contains("must not rely on `410 Gone`")
                .contains("normal\nunknown-route and security fallback behavior");
        assertThat(releaseNote).doesNotContain("current `/api/fraud-cases/**` still returns `410 Gone`");
    }

    @Test
    void fdp81ReleaseNoteDocumentsBreakingCleanupAndUnaffectedSuspiciousSummary() throws IOException {
        String releaseNote = Files.readString(resolve("docs/release/fdp_81_fraud_case_surface_cleanup.md"));

        assertThat(releaseNote)
                .contains("intentional breaking API surface cleanup")
                .contains("## Removed Routes")
                .contains("`POST /api/v1/fraud-cases/{caseId}/assign`")
                .contains("`/api/fraud-cases/**`")
                .contains("## Retained Routes")
                .contains("`PATCH /api/v1/fraud-cases/{caseId}`")
                .contains("`GET /internal/suspicious-transactions/summary`");
    }

    @Test
    void currentFraudCaseDocsContainExplicitRemovedRouteTables() throws IOException {
        for (String document : List.of(
                "docs/api/fraud_case_api.md",
                "docs/api/api_surface_v1.md",
                "docs/product/fraud_case_management.md"
        )) {
            String content = Files.readString(resolve(document));
            assertThat(content)
                    .contains("## Removed In FDP-81")
                    .contains("| Removed route | Replacement | Notes |")
                    .contains("`GET /api/v1/fraud-cases`")
                    .contains("`GET /api/v1/fraud-cases/{caseId}/audit`")
                    .contains("`/api/fraud-cases/**`")
                    .contains("`GET /internal/suspicious-transactions/summary` remains supported");
        }
    }

    private String read(List<String> documents) throws IOException {
        StringBuilder content = new StringBuilder();
        for (String document : documents) {
            content.append(Files.readString(resolve(document))).append('\n');
        }
        return content.toString();
    }

    private Path resolve(String document) {
        Path rootPath = Path.of(document);
        return Files.exists(rootPath) ? rootPath : Path.of("..").resolve(document);
    }
}
