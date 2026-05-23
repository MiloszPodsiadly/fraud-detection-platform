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

    @Test
    void DocsMentionResponseLocalEventKeyTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("`eventKey` is not stable across source-data changes")
                .contains("current response only")
                .contains("must not be used as a persistent bookmark");
    }

    @Test
    void DocsMentionLinkedAlertContextIsNotLinkTimeHistoryTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("`LINKED_ALERT_CONTEXT`")
                .contains("is not proof of the time when the alert was linked to the fraud case")
                .doesNotContain("`FRAUD_ALERT_LINKED`");
    }

    @Test
    void DocsMentionMissingLinkedAlertsArePartialOnlyTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("Missing linked alerts are represented through `partial=true` only")
                .contains("does not return missing alert IDs or a")
                .contains("missing alert count");
    }

    @Test
    void DocsMentionBoundedLinkedAlertInputBeforeRepositoryReadTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("`MAX_LINKED_ALERTS_FOR_TIMELINE` is 50")
                .contains("limited before repository lookup")
                .contains("source read bounded");
    }

    @Test
    void DocsMentionErrorEvidenceStatusAndMetricsScopeTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("`evidenceStatus=ERROR`")
                .contains("`ALERT_EVIDENCE_SNAPSHOT_PARTIAL`")
                .contains("does not add a dedicated Micrometer metric")
                .contains("sensitive-read audit");
    }

    @Test
    void DocsMentionAuditFailurePolicyTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("Sensitive-read audit failures follow the existing sensitive-read endpoint policy")
                .contains("fail the read instead of being silently swallowed");
    }

    private String readDocs() throws Exception {
        return Files.readString(Path.of("..", "docs", "product", "fraud_case_evidence_timeline.md"));
    }
}
