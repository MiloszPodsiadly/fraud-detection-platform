package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueCursorDocsContractTest {

    @Test
    void docsShouldDescribeFinalCursorContractWithoutLeakingSensitiveValues() throws Exception {
        String docs = Files.readString(projectRoot().resolve("docs/fdp/fdp_45_work_queue_readiness.md"))
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(docs)
                .contains("signed")
                .contains("not encrypted")
                .contains("versioned")
                .contains("bound to the canonical query shape")
                .contains("not snapshot isolation")
                .contains("does not freeze the work queue")
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
        String openApi = Files.readString(projectRoot().resolve("docs/openapi/alert_service.openapi.yaml"))
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(openApi)
                .contains("cursor values are opaque, signed, not encrypted")
                .contains("changing filters or sort with a cursor returns invalid_cursor")
                .contains("invalid_cursor_page_combination")
                .contains("cursor signing secret rotation")
                .contains("cursor is bound to filters and sort, not size")
                .contains("cursor pagination is not snapshot isolation")
                .contains("stored enum value order");
    }

    @Test
    void rotationRunbookShouldDescribeInvalidationRecoveryAndCursorHandling() throws Exception {
        String runbook = Files.readString(projectRoot().resolve("docs/runbooks/fraud_case_operations.md"))
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(runbook)
                .contains("fraud_case_work_queue_cursor_signing_secret")
                .contains("existing work queue cursors may become invalid")
                .contains("invalid_cursor")
                .contains("restart traversal without a cursor")
                .contains("treat the cursor as opaque")
                .contains("do not parse cursor")
                .contains("do not log cursor")
                .contains("signed for integrity, not encrypted")
                .contains("not data loss")
                .contains("not a lifecycle mutation failure")
                .contains("not an audit failure");
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
