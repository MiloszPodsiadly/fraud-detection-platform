package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseLegacyExactCountCompatibilityDocsTest {

    @Test
    void docsAndSourceConfirmRemovedListDoesNotRetainExactCount() throws IOException {
        String docs = Files.readString(projectRoot().resolve("docs/architecture/fraud_case_work_queue.md"))
                .toLowerCase()
                .replaceAll("\\s+", " ");
        String queryService = Files.readString(sourceRoot().resolve("service/FraudCaseQueryService.java"));
        String repository = Files.readString(sourceRoot().resolve("fraudcase/MongoFraudCaseSearchRepository.java"));

        assertThat(docs)
                .contains("removed general fraud-case list route is not an active compatibility path")
                .contains("work queue does not perform an exact count");

        assertThat(queryService)
                .contains("searchRepository.searchSlice(")
                .doesNotContain("searchRepository.search(");
        assertThat(repository).doesNotContain("mongoTemplate.count(", "public Page<FraudCaseDocument> search");
        assertThat(searchSliceMethod(repository)).doesNotContain(".count(");
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
