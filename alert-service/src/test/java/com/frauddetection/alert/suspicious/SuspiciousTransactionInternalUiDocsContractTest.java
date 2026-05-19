package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionInternalUiDocsContractTest {

    private static final Path DOCS = Path.of("../docs/product/suspicious_transaction_internal_ui.md");

    @Test
    void documentsReadOnlyUiSemanticsAndExistingBackendContract() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("FDP-66 internal read-only UI")
                .contains("protected read API")
                .contains("GET `/internal/suspicious-transactions/summary`")
                .contains("GET `/internal/suspicious-transactions`")
                .contains("GET `/internal/suspicious-transactions/{suspiciousTransactionId}`")
                .contains("System-detected suspicious signal")
                .contains("Not confirmed fraud")
                .contains("Not an analyst decision")
                .contains("Not a final outcome")
                .contains("The frontend guard is not a security boundary")
                .contains("Backend authorization remains authoritative for all internal read endpoints")
                .contains("linkedAlertId as a reference only")
                .contains("reasonCodes as metadata only")
                .contains("evidenceStatus, evidenceSnapshotItemCount, and evidenceProjectionState as metadata only")
                .contains("must not display full evidence snapshots");
    }

    @Test
    void docsMentionAcceptedSummaryEndpointScope() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("Accepted FDP-66 scope is limited to")
                .contains("UI read-only list and detail views")
                .contains("existing cursor list and detail API")
                .contains("Only additive backend API change allowed in FDP-66 is GET /internal/suspicious-transactions/summary for workspace aggregate counter");
    }

    @Test
    void docsSaySummaryIsAggregateOnly() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("requires `SUSPICIOUS_TRANSACTION_READ`")
                .contains("audited as")
                .contains("aggregate read")
                .contains("returns only `totalSuspiciousTransactions`")
                .contains("workspace-level aggregate counter");
    }

    @Test
    void docsSaySummaryIsNotPaginationTotal() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("not pagination total metadata")
                .contains("not page count")
                .contains("not total pages")
                .contains("must not be used for")
                .contains("page-number navigation");
    }

    @Test
    void docsSaySummaryIsNotFraudOutcome() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("not a final outcome")
                .contains("not a confirmed fraud count");
    }

    @Test
    void docsSaySummaryIsNotAnalystWorkload() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("not analyst workload")
                .contains("not a fraud-case count");
    }

    @Test
    void documentsCursorUiWithoutTotalPaginationOrWorkflowActions() throws IOException {
        String docs = Files.readString(DOCS);
        String lower = docs.toLowerCase(Locale.ROOT);

        assertThat(docs)
                .contains("cursor-based `Load next`")
                .contains("workspace suspicious signal total returned by the dedicated summary endpoint")
                .contains("cursor list itself must not expose a page-scoped total")
                .contains("total pages")
                .contains("page number navigation")
                .contains("No confirm, dismiss, submit, link-case, assign, claim, export, or bulk action");

        assertThat(lower)
                .doesNotContain("is a fraud verdict")
                .doesNotContain("is fraud confirmation")
                .doesNotContain("is an analyst disposition")
                .doesNotContain("is a legal-proof claim")
                .doesNotContain("adds backend behavior")
                .doesNotContain("write endpoint is available");
    }
}
