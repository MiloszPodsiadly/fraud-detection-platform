package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueCursorDocsContractTest {

    @Test
    void docsShouldDescribeFinalCursorContractWithoutLeakingSensitiveValues() throws Exception {
        String docs = Files.readString(projectRoot().resolve("docs/fdp-45-work-queue-readiness.md"))
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(docs)
                .contains("signed")
                .contains("versioned")
                .contains("bound to the canonical query shape")
                .contains("changing filters or sort")
                .contains("invalid_cursor")
                .contains("invalid_cursor_page_combination")
                .contains("restart traversal without a cursor")
                .contains("size is not part of the cursor")
                .contains("rotating the cursor signing secret")
                .contains("stored enum value order")
                .contains("must not parse")
                .contains("must not log the cursor");
    }

    @Test
    void openApiShouldDescribeFilterBoundCursorAndPageConflict() throws Exception {
        String openApi = Files.readString(projectRoot().resolve("docs/openapi/alert-service.openapi.yaml"))
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(openApi)
                .contains("cursor values are opaque, signed, versioned, bound to")
                .contains("changing filters or sort with a cursor returns invalid_cursor")
                .contains("invalid_cursor_page_combination")
                .contains("cursor signing secret rotation")
                .contains("cursor is bound to filters and sort, not size")
                .contains("stored enum value order");
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
