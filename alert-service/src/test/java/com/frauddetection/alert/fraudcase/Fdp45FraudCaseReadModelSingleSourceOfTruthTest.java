package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseReadModelSingleSourceOfTruthTest {

    @Test
    void workQueueUsesExistingQueryServiceAndSearchRepositoryOnly() throws IOException {
        String controller = read("controller/FraudCaseController.java");
        String queryService = read("service/FraudCaseQueryService.java");
        String searchRepository = read("fraudcase/MongoFraudCaseSearchRepository.java");

        assertThat(controller)
                .contains("fraudCaseManagementService.workQueue(")
                .doesNotContain("fraudCaseManagementService.searchCases(")
                .doesNotContain("fraudCaseManagementService.listCases(pageable)")
                .doesNotContain("MongoTemplate")
                .doesNotContain("Criteria.where")
                .doesNotContain("new Query(");
        assertThat(queryService)
                .contains("searchRepository.search(")
                .contains("new FraudCaseSearchCriteria(")
                .doesNotContain("MongoTemplate")
                .doesNotContain("Criteria.where")
                .doesNotContain("new Query(");
        assertThat(searchRepository)
                .contains("class MongoFraudCaseSearchRepository")
                .contains("stableSort(")
                .contains("Sort.Order.asc(\"_id\")");
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(sourceRoot().resolve(Path.of(relativePath)));
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        if (Files.exists(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
