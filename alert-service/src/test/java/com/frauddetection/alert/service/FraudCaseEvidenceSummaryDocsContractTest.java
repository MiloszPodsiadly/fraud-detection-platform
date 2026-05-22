package com.frauddetection.alert.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseEvidenceSummaryDocsContractTest {

    @Test
    void FraudCaseEvidenceSummaryDocsContractTest() throws Exception {
        String docs = Files.readString(Path.of("..", "docs", "product", "fraud_case_evidence_summary.md"));

        assertThat(docs)
                .contains("read-only investigation context projection")
                .contains("GET /api/v1/fraud-cases/{caseId}/evidence-summary")
                .contains("requires `fraud-case:read`")
                .contains("## Scope Boundary")
                .contains("FraudCaseDocument.linkedAlertIds")
                .contains("AlertDocument.evidenceSnapshot")
                .contains("Direct SuspiciousTransaction aggregation is intentionally out of scope")
                .contains("enum-derived product copy for title and description")
                .contains("Missing fraud cases are audited as `REJECTED`")
                .contains("LINKED_ALERT_LIMIT_EXCEEDED")
                .contains("source coverage is complete")
                .contains("not fraud confirmation")
                .contains("not a case decision")
                .contains("not a final outcome")
                .contains("not an analyst disposition")
                .contains("legal proof")
                .contains("not a complete investigation view")
                .contains("Raw alert ids, transaction ids, customer ids, account identifiers, correlation ids")
                .contains("raw evidence title or description text")
                .contains("not persist a new document, create or edit evidence, mutate case");
    }

    @Test
    void FraudCaseEvidenceSummaryDocsMentionSuspiciousTransactionAggregationOutOfScopeTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("## Direct SuspiciousTransaction Aggregation")
                .contains("FDP-73 does not directly query or aggregate SuspiciousTransaction documents")
                .contains("SuspiciousTransaction-derived context may appear only if it has already been materialized")
                .contains("FDP-73 v1 summarizes evidence from alerts linked to the FraudCase")
                .contains("FraudCaseDocument.linkedAlertIds")
                .contains("AlertDocument.evidenceSnapshot")
                .contains("not complete case evidence");
    }

    @Test
    void FraudCaseEvidenceSummaryDocsMentionLinkedAlertSliceOnlyTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("Direct SuspiciousTransaction aggregation is intentionally out of scope")
                .contains("FraudCaseDocument.linkedAlertIds")
                .contains("AlertDocument.evidenceSnapshot");
    }

    @Test
    void FraudCaseEvidenceSummaryDocsMentionTruncationPreventsAvailableTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("source coverage is complete")
                .contains("truncated=true")
                .contains("aggregate evidence status is `PARTIAL`");
    }

    @Test
    void FraudCaseEvidenceSummaryDocsMentionRawTitleDescriptionNotResponseFieldsTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("raw evidence title or description text")
                .contains("not response fields");
    }

    @Test
    void FraudCaseEvidenceSummaryDocsMentionLinkedAlertCountSemanticsTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("`linkedAlertCount` is the total normalized linked alert count on the FraudCase")
                .contains("not the number of alerts processed")
                .contains("LINKED_ALERT_LIMIT_EXCEEDED")
                .contains("prevents `AVAILABLE`");
    }

    @Test
    void FraudCaseEvidenceSummaryDocsMentionMissingLinkedAlertCountAbsenceTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("FDP-73 does not expose `missingLinkedAlertCount`")
                .contains("Missing linked alert coverage is represented through")
                .contains("partial=true")
                .contains("aggregateEvidenceStatus=PARTIAL");
    }

    @Test
    void FraudCaseEvidenceSummaryDocsMentionApiV1CompatibilityContractTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("Because this endpoint is under `/api/v1`")
                .contains("internal product API contract")
                .contains("avoid changing the meaning of `aggregateEvidenceStatus`, `partial`, `legacy`")
                .contains("without a documented migration");
    }

    @Test
    void FraudCaseEvidenceSummaryDocsMentionNotApplicablePreventsAvailableTest() throws Exception {
        String docs = readDocs();

        assertThat(docs)
                .contains("`NOT_APPLICABLE` is treated conservatively as preventing aggregate `AVAILABLE`")
                .contains("the summary cannot claim all evidence is available and applicable");
    }

    private String readDocs() throws Exception {
        return Files.readString(Path.of("..", "docs", "product", "fraud_case_evidence_summary.md"));
    }
}
