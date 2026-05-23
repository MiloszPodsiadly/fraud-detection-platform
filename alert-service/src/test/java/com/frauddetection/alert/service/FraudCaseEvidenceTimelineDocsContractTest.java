package com.frauddetection.alert.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseEvidenceTimelineDocsContractTest {

    @Test
    void DocsMentionTimelineIsNotAuditHistoryTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("This timeline is not:")
                .contains("audit trail")
                .contains("legal record")
                .contains("complete lifecycle timeline")
                .contains("analyst decision history");
    }

    @Test
    void DocsMentionTimelineDoesNotCreateHistoryTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("Timeline does not create history")
                .contains("No workflow history reconstruction")
                .contains("No `CASE_STATUS_CHANGED` from current status or `updatedAt`");
    }

    @Test
    void DocsMentionNoRawIdentifiersTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("No alert IDs are returned")
                .contains("No transaction IDs are returned")
                .contains("No customer or account IDs are returned")
                .contains("No correlation IDs are returned")
                .contains("No evidence IDs are returned");
    }

    @Test
    void DocsMentionDeferredLifecycleEventsTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("Deferred:")
                .contains("`CASE_STATUS_CHANGED`")
                .contains("`ANALYST_DECISION_RECORDED`")
                .contains("Only add deferred event types if an explicit timestamped read-safe source exists");
    }

    private String readDocs() throws Exception {
        return Files.readString(Path.of("..", "docs", "product", "fraud_case_evidence_timeline.md"));
    }
}
