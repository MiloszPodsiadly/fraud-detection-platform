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
                .contains("SuspiciousTransaction internal read-only UI")
                .contains("protected read API")
                .contains("GET `/internal/suspicious-transactions/summary`")
                .contains("GET `/internal/suspicious-transactions`")
                .contains("GET `/internal/suspicious-transactions/{suspiciousTransactionId}`")
                .contains("GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`")
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
                .contains("Current SuspiciousTransaction internal UI scope is limited to")
                .contains("UI read-only list and detail views")
                .contains("cursor list, detail, summary, and linked-alert context read APIs")
                .contains("Relationship-validated linked alert context");
    }

    @Test
    void docsSaySummaryIsAggregateOnly() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("requires `SUSPICIOUS_TRANSACTION_READ`")
                .contains("audited as")
                .contains("aggregate read")
                .contains("returns `totalSuspiciousTransactions`")
                .contains("workspace-level aggregate counter");
    }

    @Test
    void docsMentionSummaryIsCachedOrMaterialized() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("cached or materialized")
                .contains("fresh cached value");
    }

    @Test
    void docsMentionSummaryTtl() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("configurable TTL")
                .contains("30 seconds");
    }

    @Test
    void docsMentionSummaryFreshness() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("freshness")
                .contains("freshness=STALE")
                .contains("freshness=UNAVAILABLE");
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
    void docsSaySummaryIsNotFraudCount() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs).contains("not a confirmed fraud count");
    }

    @Test
    void docsSaySummaryIsNotAnalystWorkload() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("not analyst workload")
                .contains("not a fraud-case count");
    }

    @Test
    void docsSaySummaryDoesNotLiveCountEveryRequest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs).contains("The summary endpoint must not execute a global collection count on every request.");
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

    @Test
    void docsMentionFdp70LinkedAlertResolverMigration() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("The SuspiciousTransaction linked-alert UI uses the backend relationship resolver")
                .contains("AlertReadOnlyContextPage calls GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`")
                .contains("The UI does not call GET `/api/v1/alerts/{alertId}` for SuspiciousTransaction linked-alert context")
                .contains("The frontend sends `suspiciousTransactionId` only and does not send `alertId` or `linkedAlertId` to the resolver");
    }

    @Test
    void DocsCurrentStateResolverContractTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("The SuspiciousTransaction linked-alert UI uses the backend relationship resolver")
                .contains("The frontend sends `suspiciousTransactionId` only")
                .contains("Backend relationship validation is authoritative")
                .contains("HTTP 200 does not imply available context");
    }

    @Test
    void DocsMentionRouterOwnsReadinessTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs).contains("WorkspaceDetailRouter owns route/source readiness");
    }

    @Test
    void DocsMentionComponentOwnsResolverStateRenderingTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs).contains("AlertReadOnlyContextPage owns resolver state rendering");
    }

    @Test
    void DocsMentionNoFrontendRelationshipValidationTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs).contains("No frontend relationship validation is a source of truth");
    }

    @Test
    void DocsMentionSourceIdMatchIsRouteReadinessTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("sourceSuspiciousTransaction.suspiciousTransactionId")
                .contains("selected route")
                .contains("UX route readiness, not linked-alert relationship validation");
    }

    @Test
    void DocsMentionSourceIdMismatchFailsClosedBeforeFetchTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs).contains("A source identifier mismatch fails closed before any linked-alert resolver fetch");
    }

    @Test
    void DocsMentionStaleSourceMismatchExplicitStateTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("A known source identifier mismatch is not treated as normal loading")
                .contains("explicit fail-closed")
                .contains("stale-source/source-mismatch state without raw identifiers");
    }

    @Test
    void DocsMentionFrontendDoesNotValidateLinkedAlertRelationshipTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("The frontend does not compare `linkedAlertId`, alert transaction, customer, account, correlation, or score decision")
                .contains("to validate the linked-alert relationship");
    }

    @Test
    void DocsMentionBackendOwnsRelationshipValidationTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("The backend owns linked-alert relationship validation")
                .contains("Backend relationship validation is authoritative");
    }

    @Test
    void DocsMentionSuspiciousTransactionIdOnlyTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("The frontend sends `suspiciousTransactionId` only")
                .contains("`linkedAlertId` may be displayed as reference-only SuspiciousTransaction detail context")
                .contains("but it does not drive the linked-alert context fetch");
    }

    @Test
    void DocsMentionNoAlertIdInHeadersQueryOrBodyTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("The backend must not accept `alertId` in the path, query string, request body, or custom headers")
                .contains("must not send `linkedAlertId`, customerId, accountId, transactionId, correlationId, or scoreDecisionId")
                .contains("query parameters, request body, or custom headers");
    }

    @Test
    void DocsMentionNoFallbackToGeneralAlertLookupTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs).contains("The UI does not call GET `/api/v1/alerts/{alertId}` for SuspiciousTransaction linked-alert context");
    }

    @Test
    void DocsMentionBackendRelationshipValidationAuthoritativeTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs).contains("Backend relationship validation is authoritative");
    }

    @Test
    void DocsClarifyInternalEndpointIsProtectedAnalystConsoleApiTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("non-public product API for the protected analyst console")
                .contains("does not mean service-private")
                .contains("backend authorization")
                .contains("HTTP 200 does not imply available context");
    }

    @Test
    void DocsMentionLinkedAlertContextUsesMinimalBackendDtoTest() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("AlertLinkedContextResponse")
                .contains("not consume full `AlertDetailsResponse`")
                .contains("minimal allowlisted DTO")
                .contains("Non-available states render no alert fields");
    }

    @Test
    void docsMentionStateDrivenLinkedAlertRendering() throws IOException {
        String docs = Files.readString(DOCS);

        assertThat(docs)
                .contains("HTTP 200 does not imply available context")
                .contains("the UI evaluates response.state before rendering")
                .contains("Only state `LINKED_ALERT_AVAILABLE` renders alert fields")
                .contains("Non-available states render no alert fields");
    }
}
