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
                .contains("AlertDocument.evidenceSnapshot")
                .contains("LINKED_ALERT_LIMIT_EXCEEDED")
                .contains("not fraud confirmation")
                .contains("not a case decision")
                .contains("not a final outcome")
                .contains("not an analyst disposition")
                .contains("legal proof")
                .contains("not a complete investigation view")
                .contains("Raw alert ids, transaction ids, customer ids, account identifiers, correlation ids")
                .contains("not persist a new document, create or edit evidence, mutate case");
    }
}
