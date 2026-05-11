package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseReadPolicyNamingBoundaryTest {

    @Test
    void readPolicyNameCoversLegacyWorkQueueAndRepositoryBounds() throws IOException {
        Path mainRoot = sourceRoot();
        Path readPolicy = mainRoot.resolve("fraudcase/FraudCaseReadQueryPolicy.java");

        assertThat(readPolicy).exists();
        assertThat(mainRoot.resolve("fraudcase/FraudCaseWorkQueueQueryPolicy.java")).doesNotExist();

        String policy = Files.readString(readPolicy);
        String controller = Files.readString(mainRoot.resolve("controller/FraudCaseController.java"));
        String repository = Files.readString(mainRoot.resolve("fraudcase/MongoFraudCaseSearchRepository.java"));

        assertThat(policy)
                .contains("validateWorkQueuePagination")
                .contains("validateLegacyListPagination")
                .contains("validateRepositoryPageBounds")
                .contains("stableReadSort");
        assertThat(controller)
                .contains("FraudCaseReadQueryPolicy")
                .doesNotContain("FraudCaseWorkQueueQueryPolicy");
        assertThat(repository)
                .contains("FraudCaseReadQueryPolicy")
                .contains("validateRepositoryPageBounds")
                .doesNotContain("FraudCaseWorkQueueQueryPolicy");
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        if (Files.exists(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
