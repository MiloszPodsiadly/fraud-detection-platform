package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionReadApiDocumentationContractTest {

    @Test
    void docsDescribeProtectedReadOnlyInternalApiAndNonClaims() throws Exception {
        String docs = Files.readString(Path.of("../docs/product/suspicious_transaction_read_api.md"));

        assertThat(docs)
                .contains("protected")
                .contains("read-only")
                .contains("SUSPICIOUS_TRANSACTION_READ")
                .contains("size max is 100")
                .contains("slice-style pagination")
                .contains("hasNext")
                .contains("totalElements")
                .contains("totalPages")
                .contains("full collection count")
                .contains("fetching at most size + 1")
                .contains("avoids unbounded count scans")
                .contains("max size = 100")
                .contains("detectedAt")
                .contains("updatedAt")
                .contains("riskScore")
                .contains("riskLevel")
                .contains("status")
                .contains("does not mean confirmed fraud")
                .contains("does not expose analyst decision")
                .contains("does not expose final outcome")
                .contains("does not expose a full evidence snapshot")
                .contains("No write endpoint")
                .contains("No export")
                .contains("No bulk endpoint");
        assertThat(docs).doesNotContain("fraud-transactions", "FraudTransaction");
    }
}
