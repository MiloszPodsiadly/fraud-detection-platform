package com.frauddetection.alert.security.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
