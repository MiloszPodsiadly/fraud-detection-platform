package com.frauddetection.alert.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseReadModelObservabilityDocsContractTest {

    @Test
    void FraudCaseReadModelObservabilityDocsContractTest() throws Exception {
        String docs = Files.readString(Path.of("..", "docs", "product", "fraud_case_read_model_observability_contract.md"));

        assertThat(docs)
                .contains("fraud.fraud_case.read_model.read")
                .contains("`endpoint`")
                .contains("`evidence_summary`, `evidence_timeline`")
                .contains("`available`, `partial`, `legacy`, `truncated`, `empty`, `not_found`, `error`")
                .contains("Metric recording failure is isolated")
                .contains("sensitive-read audit behavior")
                .contains("must not use raw identifiers")
                .contains("request path, URI, query string, or query parameters")
                .contains("exception class names or messages")
                .contains("not fraud confirmation")
                .contains("not a case decision")
                .contains("not a final outcome");
    }
}
