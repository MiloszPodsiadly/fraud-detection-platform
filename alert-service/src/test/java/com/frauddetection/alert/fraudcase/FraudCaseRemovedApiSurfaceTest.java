package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseRemovedApiSurfaceTest {

    @Test
    void retiredLifecycleSurfaceAndImplementationStayRemoved() throws IOException {
        Path source = sourceRoot();
        String controller = Files.readString(source.resolve("controller/FraudCaseController.java"));
        String management = Files.readString(source.resolve("fraudcase/FraudCaseManagementService.java"));
        String query = Files.readString(source.resolve("fraudcase/FraudCaseQueryService.java"));

        assertThat(controller)
                .doesNotContain("/assign", "/notes", "/decisions", "/transition", "/close", "/reopen", "/audit")
                .doesNotContain("@PostMapping");
        assertThat(management)
                .doesNotContain("FraudCaseLifecycleService", "createCase(", "assignCase(", "addNote(", "addDecision(",
                        "transitionCase(", "closeCase(", "reopenCase(", "auditTrail(", "searchCases(", "listCases(");
        assertThat(query).doesNotContain("auditTrail(", "searchCases(", "listCases(", "FraudCaseAuditRepository");
        assertThat(source.resolve("fraudcase/FraudCaseLifecycleService.java")).doesNotExist();
        assertThat(source.resolve("fraudcase/FraudCaseLifecycleIdempotencyService.java")).doesNotExist();
        assertThat(source.resolve("persistence/FraudCaseLifecycleIdempotencyRepository.java")).doesNotExist();
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        return Files.exists(moduleRoot) ? moduleRoot : Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
