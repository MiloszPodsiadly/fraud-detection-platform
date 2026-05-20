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
                .contains("/internal/suspicious-transactions/summary")
                .contains("totalSuspiciousTransactions")
                .contains("freshness")
                .contains("cachedAt")
                .contains("expiresAt")
                .contains("cached or materialized")
                .contains("configurable TTL")
                .contains("The summary endpoint must not execute a global collection count on every request.")
                .contains("fraud.suspicious_transaction.summary.read")
                .contains("size max is 100")
                .contains("cursor/keyset slice pagination")
                .contains("hasNext")
                .contains("nextCursor")
                .contains("page and sort parameters are rejected")
                .contains("page number")
                .contains("totalElements")
                .contains("totalPages")
                .contains("totalCount")
                .contains("Global aggregate counting is isolated in the summary endpoint")
                .contains("offset skip")
                .contains("fetching at most size + 1")
                .contains("avoids unbounded count scans")
                .contains("max size = 100")
                .contains("Clients must navigate using nextCursor")
                .contains("must not rely on page number or total page count")
                .contains("Unfiltered search")
                .contains("bounded cursor slice")
                .contains("detectedAt DESC")
                .contains("suspiciousTransactionId DESC")
                .contains("riskLevel")
                .contains("status")
                .contains("does not mean confirmed fraud")
                .contains("does not expose analyst decision")
                .contains("does not expose final outcome")
                .contains("does not expose a full evidence snapshot")
                .contains("No write endpoint")
                .contains("No export")
                .contains("No bulk endpoint");
        assertThat(docs)
                .doesNotContain("fraud-transactions", "FraudTransaction")
                .doesNotContain("updatedAt\n- riskScore");
    }
}
