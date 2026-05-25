package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseLegacyExactCountCompatibilityDocsTest {

    @Test
    void docsAndSourceConfirmLegacyListExactCountWasRemovedByFdp81() throws IOException {
        String docs = Files.readString(projectRoot().resolve("docs/fdp/fdp_45_work_queue_readiness.md"))
                .toLowerCase()
                .replaceAll("\\s+", " ");
        String queryService = Files.readString(sourceRoot().resolve("service/FraudCaseQueryService.java"));
        String repository = Files.readString(sourceRoot().resolve("fraudcase/MongoFraudCaseSearchRepository.java"));

        assertThat(docs)
                .contains("fdp-81 later removes the general fraud-case list http")
                .contains("retaining the dedicated work queue contract");

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
