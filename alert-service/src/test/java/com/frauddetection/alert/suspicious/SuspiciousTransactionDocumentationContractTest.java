package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionDocumentationContractTest {

    @Test
    void docsIncludeNonClaimsScopeAndIdempotency() throws IOException {
        String docs = Files.readString(Path.of("../docs/product/suspicious_transactions.md"));

        assertThat(docs)
                .contains("SuspiciousTransaction is not confirmed fraud")
                .contains("SuspiciousTransaction is not an alert")
                .contains("SuspiciousTransaction is not a fraud case")
                .contains("SuspiciousTransaction is not analyst decision")
                .contains("SuspiciousTransaction is not final outcome")
                .contains("SuspiciousTransaction is not legal proof")
                .contains("FDP-60 does not store the full evidence snapshot")
                .contains("minimal evidence metadata only")
                .contains("`evidenceStatus` is conservative summary metadata")
                .contains("`AVAILABLE` means no known degradation")
                .contains("mixed AVAILABLE and degraded items")
                .contains("must not be AVAILABLE")
                .contains("does not store the full evidence snapshot")
                .contains("Duplicate-key race handling")
                .contains("transactionId + sourceEventId")
                .contains("unique index protected the idempotency invariant")
                .contains("duplicate_retry")
                .contains("not recorded as projection_error")
                .contains("Readback must not use transactionId alone")
                .contains("must not overwrite an existing linkedAlertId")
                .contains("FDP-61 does not add public API, UI, case lifecycle mutation, or new statuses")
                .contains("transactionId plus sourceEventId")
                .contains("transactionId alone is not sufficient");
    }

    @Test
    void docsDoNotClaimFraudVerdictFinalOutcomeOrLegalProof() throws IOException {
        String docs = Files.readString(Path.of("../docs/product/suspicious_transactions.md"))
                .toLowerCase(Locale.ROOT);

        assertThat(docs)
                .doesNotContain("is confirmed fraud")
                .doesNotContain("is final outcome")
                .doesNotContain("is legal proof")
                .doesNotContain("fraud verdict")
                .doesNotContain("confirmed fraud signal")
                .doesNotContain("final outcome signal")
                .doesNotContain("analyst disposition")
                .doesNotContain("legal proof claim")
                .doesNotContain("adds public api")
                .doesNotContain("adds ui");
    }
}
