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
}
