package com.frauddetection.alert.security.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp49NoNewBusinessSemanticsTest {

    @Test
    void docsLockFdp49ToSecurityArchitectureHardeningOnly() throws IOException {
        String doc = Files.readString(SecurityRuleSource.repositoryFile("docs/security/endpoint-authorization-map.md"));

        assertThat(doc)
                .contains("No new business endpoints")
                .contains("fraud-case lifecycle changes")
                .contains("idempotency changes")
                .contains("RegulatedMutationCoordinator")
                .contains("Kafka/outbox/finality changes")
                .contains("export workflow")
                .contains("bulk action workflow")
                .contains("assignment workflow")
                .contains("UI feature work");
    }

    @Test
    void noFdp49ControllerOrMutationImplementationsWereAdded() throws IOException {
        Path sourceRoot = SecurityRuleSource.repositoryFile("alert-service/src/main/java/com/frauddetection/alert");
        try (var stream = Files.walk(sourceRoot)) {
            assertThat(stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getFileName().toString().contains("Fdp49"))
                    .toList())
                    .as("FDP-49 must not add business semantics classes")
                    .isEmpty();
        }
    }

    @Test
    void fdp49DoesNotTouchForbiddenBusinessSemanticSourceAreas() throws IOException {
        List<String> forbiddenAreas = List.of(
                "alert-service/src/main/java/com/frauddetection/alert/service/FraudCaseManagementService.java",
                "alert-service/src/main/java/com/frauddetection/alert/regulated/RegulatedMutationCoordinator.java",
                "alert-service/src/main/java/com/frauddetection/alert/outbox",
                "alert-service/src/main/java/com/frauddetection/alert/export",
                "analyst-console-ui/src/components",
                "analyst-console-ui/src/fraudCases"
        );

        for (String forbiddenArea : forbiddenAreas) {
            Path path = SecurityRuleSource.repositoryFile(forbiddenArea);
            if (Files.exists(path)) {
                assertThat(filesMentioningFdp49(path))
                        .as("FDP-49 must remain security hardening only; forbidden area contains FDP-49-specific changes: "
                                + forbiddenArea)
                        .isEmpty();
            }
        }
    }

    @Test
    void endpointAuthorizationDocsKeepBusinessSemanticNonGoalsExplicit() throws IOException {
        String doc = Files.readString(SecurityRuleSource.repositoryFile("docs/security/endpoint-authorization-map.md"));

        assertThat(doc)
                .contains("does not add business endpoints")
                .contains("change fraud-case lifecycle semantics")
                .contains("change idempotency behavior")
                .contains("change `RegulatedMutationCoordinator`")
                .contains("change Kafka/outbox/finality behavior")
                .contains("change export workflows")
                .contains("change bulk action workflows")
                .contains("change assignment workflows");
    }

    private List<Path> filesMentioningFdp49(Path root) throws IOException {
        if (Files.isRegularFile(root)) {
            return Files.readString(root).contains("FDP-49") ? List.of(root) : List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".js")
                            || path.toString().endsWith(".jsx") || path.toString().endsWith(".ts")
                            || path.toString().endsWith(".tsx"))
                    .filter(path -> SecurityRuleSource.sourceFromPath(path).contains("FDP-49"))
                    .toList();
        }
    }
}
