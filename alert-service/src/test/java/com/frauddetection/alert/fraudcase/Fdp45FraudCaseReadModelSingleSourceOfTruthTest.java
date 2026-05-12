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
        String summaryController = read("controller/FraudCaseWorkQueueSummaryController.java");
        String queryService = read("service/FraudCaseQueryService.java");
        String searchRepository = read("fraudcase/MongoFraudCaseSearchRepository.java");
        String queryPolicy = read("fraudcase/FraudCaseReadQueryPolicy.java");

        assertThat(controller)
                .contains("@GetMapping(\"/work-queue\")")
                .contains("fraudCaseManagementService.workQueue(")
                .contains("FraudCaseReadQueryPolicy")
                .doesNotContain("MongoTemplate")
                .doesNotContain("Criteria.where")
                .doesNotContain("new Query(");
        assertThat(summaryController)
                .contains("@RequestMapping(\"/api/v1/fraud-cases/work-queue\")")
                .contains("fraudCaseManagementService.workQueueSummary()")
                .doesNotContain("\"/api/fraud-cases");
        assertThat(queryService)
                .contains("searchRepository.searchSlice(")
                .contains("workQueueSummary()")
                .contains("new FraudCaseSearchCriteria(")
                .doesNotContain("MongoTemplate")
                .doesNotContain("Criteria.where")
                .doesNotContain("new Query(");
        assertThat(searchRepository)
                .contains("class MongoFraudCaseSearchRepository")
                .contains("searchSlice(")
                .contains("stableReadSort(");
        assertThat(queryPolicy)
                .contains("Sort.Order.asc(TIE_BREAKER_FIELD)");
        assertThat(searchSliceMethod(searchRepository)).doesNotContain(".count(");
    }

    private String searchSliceMethod(String source) {
        int start = source.indexOf("public Slice<FraudCaseDocument> searchSlice");
        int end = source.indexOf("private List<Criteria> criteria", start);
        return source.substring(start, end);
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
