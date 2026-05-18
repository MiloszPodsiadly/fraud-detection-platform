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
                .doesNotContain("fraud verdict");
    }
}
