package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseLegacyExactCountCompatibilityDocsTest {

    @Test
    void docsAndSourceSeparateWorkQueueSliceFromListExactCountDebt() throws IOException {
        String docs = Files.readString(projectRoot().resolve("docs/fdp-45-work-queue-readiness.md"))
                .toLowerCase()
                .replaceAll("\\s+", " ");
        String queryService = Files.readString(sourceRoot().resolve("service/FraudCaseQueryService.java"));
        String repository = Files.readString(sourceRoot().resolve("fraudcase/MongoFraudCaseSearchRepository.java"));

        assertThat(docs)
                .contains("bounded slice")
                .contains("does not perform an exact mongo count")
                .contains("list exact count remains compatibility debt")
                .contains("high-volume investigator queues should use the dedicated work queue slice endpoint")
                .contains("future hardening item");
        assertThat(docs)
                .doesNotContain("all fraud-case read endpoints avoid exact count")
                .doesNotContain("list avoids exact count");

        assertThat(queryService)
                .contains("searchRepository.searchSlice(")
                .contains("searchRepository.search(");
        assertThat(searchMethod(repository)).contains("mongoTemplate.count(");
        assertThat(searchSliceMethod(repository)).doesNotContain(".count(");
    }

    private String searchMethod(String source) {
        int start = source.indexOf("public Page<FraudCaseDocument> search");
        int end = source.indexOf("@Override", start + 1);
        return source.substring(start, end);
    }

    private String searchSliceMethod(String source) {
        int start = source.indexOf("public Slice<FraudCaseDocument> searchSlice");
        int end = source.indexOf("private List<Criteria> criteria", start);
        return source.substring(start, end);
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        if (Files.exists(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
