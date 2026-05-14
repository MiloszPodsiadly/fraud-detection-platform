package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseLegacyPaginationReleaseNotesTest {

    @Test
    void docsShouldMakeListMaxPageSafetyBoundaryExplicit() throws IOException {
        String docs = Files.readString(projectRoot().resolve("docs/fdp-45-work-queue-readiness.md"))
                .toLowerCase()
                .replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("list pagination bound")
                .contains("get /api/v1/fraud-cases")
                .contains("page > 1000")
                .contains("abuse prevention safety boundary")
                .contains("pagedresponse")
                .contains("compatibility path")
                .contains("cursor pagination");
        assertThat(docs).doesNotContain("get /api/fraud-cases");
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
