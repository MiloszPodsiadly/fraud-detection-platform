package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AlertReadOnlyDetailBridgeDocsContractTest {

    @Test
    void alertReadOnlyDetailBridgeDocsContractTest() throws Exception {
        String docs = Files.readString(docPath());
        String lower = docs.toLowerCase(Locale.ROOT);

        assertThat(lower).contains("read-only navigation");
        assertThat(docs).contains("GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`");
        assertThat(lower).contains("backend relationship validation is authoritative");
        assertThat(lower).contains("clients cannot pass `alertid`");
        assertThat(lower).contains("does not mutate");
        assertThat(lower).contains("alert_read");
        assertThat(lower).contains("suspicious_transaction_read");
        assertThat(lower).contains("does not imply alert read access");
        assertThat(lower).contains("does not expose assistant summary");
        assertThat(lower).contains("does not expose an evidence proof panel");
        assertThat(lower).contains("not confirmed fraud");
        assertThat(lower).contains("not an analyst decision");
        assertThat(lower).contains("not a final outcome");
        assertThat(lower).contains("not a case lifecycle action");
        assertThat(lower).contains("not legal proof");
    }

    @Test
    void docsMentionSourceSuspiciousTransactionContext() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("linked alert context is opened from suspicioustransaction detail view");
        assertThat(lower).contains("route/state may retain `suspicioustransactionid`");
        assertThat(lower).contains("backend loads the source suspicioustransaction");
    }

    @Test
    void docsSayAlertIdAloneIsNotLinkedBridgeContext() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("an `alertid` alone in the suspicious workspace is invalid bridge context");
    }

    @Test
    void docsSayContextBindingIsNotSecurityBoundary() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("router source readiness is ux route readiness, not frontend relationship validation");
        assertThat(lower).contains("frontend guard is not a security boundary");
    }

    @Test
    void docsSayBackendAuthorizationRemainsAuthoritative() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("backend relationship validation and alert-read authorization remain authoritative");
        assertThat(lower).contains("`suspicious_transaction_read` does not imply `alert_read`");
    }

    @Test
    void docsMentionNoAlertFetchBeforeSourceVerification() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("clients cannot pass `alertid`");
        assertThat(lower).contains("backend derives `linkedalertid`");
    }

    @Test
    void docsMentionSourceLinkedAlertMustMatchSelectedAlert() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("validates the relationship");
        assertThat(lower).contains("relationship does not match");
    }

    @Test
    void docsMentionSourceMissingFailsClosed() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("loads the source suspicioustransaction");
        assertThat(lower).contains("fails closed");
    }

    @Test
    void docsMentionContextBindingIsNotSecurityBoundary() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("router source readiness is ux route readiness, not frontend relationship validation");
    }

    @Test
    void DocsMentionDedicatedAlertReadOnlyContextPageTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("`alertreadonlycontextpage` remains the dedicated ui component");
        assertThat(lower).contains("dedicated read-only alert context page");
    }

    @Test
    void DocsSaySuspiciousBridgeDoesNotUseAlertDetailsPageTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("`alertdetailspage` remains the workflow-capable normal alert detail page");
        assertThat(lower).contains("`alertreadonlycontextpage` is the dedicated read-only alert context page");
    }

    @Test
    void DocsSayLinkedAlertResolverClientTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("`alertreadonlycontextpage` depends only on a linked-alert resolver client");
        assertThat(lower).contains("`alertreadonlycontextpage` does not receive the full api client");
        assertThat(lower).contains("internal linked-alert endpoint");
    }

    @Test
    void DocsSayBackendEndpointIsRelationshipValidatedTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("dedicated internal read endpoint");
        assertThat(lower).contains("backend derives `linkedalertid`");
    }

    @Test
    void DocsSayNoWorkflowNoDecisionNoAssistantSummaryTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("does not submit analyst decisions");
        assertThat(lower).contains("does not expose assistant summary");
        assertThat(lower).contains("does not expose an evidence proof panel");
        assertThat(lower).contains("does not log raw identifiers");
    }

    @Test
    void DocsSayContextBindingNotSecurityBoundaryTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("component boundary is scope control, not a frontend security boundary");
        assertThat(lower).contains("backend relationship validation and alert-read authorization remain authoritative");
    }

    @Test
    void DocsSayAlertDetailsPageNoLongerHasReadOnlyContext() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("`alertdetailspage` remains the workflow-capable normal alert detail page");
        assertThat(lower).contains("`alertreadonlycontextpage` is the dedicated read-only alert context page");
    }

    @Test
    void DocsSayAlertReadOnlyContextPageIsOnlySuspiciousBridgeComponent() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("`alertreadonlycontextpage` is the only component for suspicioustransaction linked-alert read-only context");
        assertThat(lower).contains("`alertreadonlycontextpage` depends only on a linked-alert resolver client");
    }

    @Test
    void DocsMentionUiUsesBackendRelationshipResolverTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("The SuspiciousTransaction linked-alert UI uses the backend linked-alert resolver")
                .contains("GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`");
    }

    @Test
    void DocsCurrentStateResolverContractTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("The SuspiciousTransaction linked-alert UI uses the backend linked-alert resolver")
                .contains("The frontend sends `suspiciousTransactionId` only")
                .contains("Backend relationship validation is authoritative")
                .contains("HTTP 200 does not imply available context");
    }

    @Test
    void DocsMentionRouterOwnsReadinessTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs).contains("WorkspaceDetailRouter` owns route/source readiness");
    }

    @Test
    void DocsMentionComponentOwnsResolverStateRenderingTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs).contains("AlertReadOnlyContextPage` owns resolver state rendering");
    }

    @Test
    void DocsMentionNoFrontendRelationshipValidationTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs).contains("No frontend relationship validation is a source of truth");
    }

    @Test
    void DocsMentionSourceIdMatchIsRouteReadinessTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("sourceSuspiciousTransaction.suspiciousTransactionId === selectedSuspiciousTransactionId")
                .contains("This allowed frontend check is UX route readiness, not linked-alert relationship validation");
    }

    @Test
    void DocsMentionSourceIdMismatchFailsClosedBeforeFetchTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs).contains("The source identifier mismatch fails closed before any linked-alert resolver fetch");
    }

    @Test
    void DocsMentionFrontendDoesNotValidateLinkedAlertRelationshipTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("The frontend does not validate the linked-alert relationship")
                .contains("Forbidden frontend relationship checks");
    }

    @Test
    void DocsMentionBackendOwnsRelationshipValidationTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("The backend derives `linkedAlertId`, loads the alert, validates the linked-alert relationship")
                .contains("The backend owns linked-alert relationship validation");
    }

    @Test
    void DocsMentionNoGeneralAlertLookupTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("does not call GET `/api/v1/alerts/{alertId}`")
                .contains("does not fallback to the general alert lookup");
    }

    @Test
    void DocsMentionSuspiciousTransactionIdOnlyTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("The frontend sends `suspiciousTransactionId` only")
                .contains("does not send `alertId` or `linkedAlertId` to the resolver");
    }

    @Test
    void DocsMentionNoAlertIdInHeadersQueryOrBodyTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("does not send `alertId` or `linkedAlertId` to the resolver through URL, query, body, or custom headers")
                .contains("does not send customerId, accountId, transactionId, correlationId, or scoreDecisionId to the resolver")
                .contains("through URL, query, body, or custom headers");
    }

    @Test
    void DocsMentionNoFallbackToGeneralAlertLookupTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("does not call GET `/api/v1/alerts/{alertId}`")
                .contains("does not fallback to the general alert lookup");
    }

    @Test
    void DocsMentionBackendRelationshipValidationAuthoritativeTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("Frontend state is not authoritative")
                .contains("Backend relationship validation is authoritative");
    }

    @Test
    void DocsClarifyInternalEndpointIsProtectedAnalystConsoleApiTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("non-public product API for the protected analyst console")
                .contains("does not mean service-private")
                .contains("backend authorization")
                .contains("HTTP 200 does not imply available context");
    }

    @Test
    void DocsMentionLinkedAlertContextUsesMinimalBackendDtoTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("AlertLinkedContextResponse")
                .contains("not consume full `AlertDetailsResponse`")
                .contains("minimal allowlisted DTO")
                .contains("Non-available states render no alert fields");
    }

    @Test
    void DocsMentionStateDrivenRenderingTest() throws Exception {
        String docs = Files.readString(docPath());

        assertThat(docs)
                .contains("HTTP 200 does not imply available context")
                .contains("the UI must evaluate response.state")
                .contains("Only response.state `LINKED_ALERT_AVAILABLE` may render alert fields")
                .contains("Non-available states render no alert fields");
    }

    @Test
    void DocsMentionNoWorkflowNoVerdictTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower)
                .contains("does not submit analyst decisions")
                .contains("does not add workflow")
                .contains("not confirmed fraud")
                .contains("not a final outcome");
    }

    @Test
    void DocsMentionBackendAuthAuthoritativeTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower)
                .contains("frontend guard is not a security boundary")
                .contains("backend `alert_read` authorization remains authoritative");
    }

    @Test
    void DocsSayReadOnlySafeByConstructionNotConditionals() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("makes the read-only path safe by construction");
        assertThat(lower).contains("instead of conditionals inside the workflow page");
    }

    @Test
    void alertReadOnlyDetailBridgeDocsDoNotContainOverclaimWording() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).doesNotContain("fraud confirmed");
        assertThat(lower).doesNotContain("is confirmed fraud");
        assertThat(lower).doesNotContain("is legal proof");
        assertThat(lower).doesNotContain("is a complete investigation");
        assertThat(lower).doesNotContain("is an analyst disposition");
        assertThat(lower).doesNotContain("is a case outcome");
    }

    private Path docPath() {
        return DocumentationTestSupport.docsRoot().resolve("product/alert_read_only_detail_bridge.md");
    }
}
