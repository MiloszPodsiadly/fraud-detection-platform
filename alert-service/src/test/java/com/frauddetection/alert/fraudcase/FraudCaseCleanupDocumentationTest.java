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
