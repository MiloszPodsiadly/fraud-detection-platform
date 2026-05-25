package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseCurrentApiSurfaceTest {

    @Test
    void currentFraudCaseRoutesAndRegulatedUpdateStayPresent() throws IOException {
        Path source = sourceRoot();
        String controller = Files.readString(source.resolve("controller/FraudCaseController.java"));
        String summaryController = Files.readString(source.resolve("controller/FraudCaseWorkQueueSummaryController.java"));
        String management = Files.readString(source.resolve("service/FraudCaseManagementService.java"));

        assertThat(controller)
                .contains("@GetMapping(VERSIONED_BASE_PATH + \"/work-queue\")")
                .contains("@GetMapping(VERSIONED_BASE_PATH + \"/{caseId}\")")
                .contains("@PatchMapping(VERSIONED_BASE_PATH + \"/{caseId}\")");
        assertThat(summaryController)
                .contains("@RequestMapping(\"/api/v1/fraud-cases/work-queue\")")
                .contains("@GetMapping(\"/summary\")");
        assertThat(management)
                .contains("public UpdateFraudCaseResponse updateCase(")
                .contains("regulatedMutationCoordinator.commit(command)");
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        return Files.exists(moduleRoot) ? moduleRoot : Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
