package com.frauddetection.alert.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseResponsibilityArchitectureTest {

    private static final Path SERVICE_PACKAGE = Path.of(
            "src/main/java/com/frauddetection/alert/service"
    );

    @Test
    void managementFacadeOnlyDelegatesFraudCaseWorkflows() throws IOException {
        String source = source("FraudCaseManagementService.java");

        assertThat(source)
                .contains("FraudCaseIngestionService", "FraudCaseUpdateCoordinator", "FraudCaseQueryService")
                .doesNotContain(
                        "FraudCaseRepository",
                        "ScoredTransactionRepository",
                        "RegulatedMutationCommand",
                        "FraudFeatureThresholdContract",
                        ".save("
                );
    }

    @Test
    void transactionAssemblerCannotPersistAggregateState() throws IOException {
        String source = source("FraudCaseTransactionAssembler.java");

        assertThat(source)
                .contains("ScoredTransactionRepository", "CanonicalMonetaryEvidenceMapper")
                .doesNotContain("FraudCaseRepository", ".save(", ".insert(");
    }

    @Test
    void ingestionAndAnalystUpdateHaveSeparateDependencies() throws IOException {
        String ingestion = source("FraudCaseIngestionService.java");
        String analystUpdate = source("FraudCaseUpdateCoordinator.java");

        assertThat(ingestion)
                .contains("FraudCaseRepository", "FraudCaseTransactionAssembler")
                .doesNotContain("RegulatedMutationCoordinator", "UpdateFraudCaseRequest");
        assertThat(analystUpdate)
                .contains("RegulatedMutationCoordinator", "FraudCaseUpdateMutationHandler")
                .doesNotContain("TransactionScoredEvent", "ScoredTransactionRepository");
    }

    private String source(String fileName) throws IOException {
        return Files.readString(SERVICE_PACKAGE.resolve(fileName));
    }
}
