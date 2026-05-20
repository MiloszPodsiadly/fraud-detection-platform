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
        assertThat(docs).contains("GET `/api/v1/alerts/{alertId}`");
        assertThat(lower).contains("does not introduce a second alert read api");
        assertThat(lower).contains("does not add a new backend endpoint");
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
        assertThat(lower).contains("route/state must retain `suspicioustransactionid` and `linkedalertid`");
        assertThat(lower).contains("requires source suspicioustransaction context");
    }

    @Test
    void docsSayAlertIdAloneIsNotLinkedBridgeContext() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("an `alertid` alone in the suspicious workspace is invalid bridge context");
    }

    @Test
    void docsSayContextBindingIsNotSecurityBoundary() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("frontend context binding is scope control, not security boundary");
        assertThat(lower).contains("frontend guard is not a security boundary");
    }

    @Test
    void docsSayBackendAuthorizationRemainsAuthoritative() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("backend alert-read authorization remains authoritative");
        assertThat(lower).contains("`suspicious_transaction_read` does not imply `alert_read`");
    }

    @Test
    void docsMentionNoAlertFetchBeforeSourceVerification() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("must not fetch alert detail until the source `linkedalertid` matches the selected `alertid`");
    }

    @Test
    void docsMentionSourceLinkedAlertMustMatchSelectedAlert() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("source `linkedalertid` matches the selected `alertid`");
    }

    @Test
    void docsMentionSourceMissingFailsClosed() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("without loaded source suspicioustransaction context is pending verification");
        assertThat(lower).contains("bridge fails closed");
    }

    @Test
    void docsMentionContextBindingIsNotSecurityBoundary() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("frontend context binding is scope control, not security boundary");
    }

    @Test
    void DocsMentionDedicatedAlertReadOnlyContextPageTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("fdp-68 extracts that read-only alert context into `alertreadonlycontextpage`");
        assertThat(lower).contains("fdp-68 is architecture hardening, not product feature expansion");
        assertThat(lower).contains("dedicated read-only alert context page");
    }

    @Test
    void DocsSaySuspiciousBridgeDoesNotUseAlertDetailsPageTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("fdp-68 fully removes suspicioustransaction read-only bridge mode from `alertdetailspage`");
        assertThat(lower).contains("`alertdetailspage` no longer accepts `readonlycontext` for the suspicioustransaction bridge");
        assertThat(lower).contains("`alertdetailspage` remains the workflow-capable normal alert detail page");
    }

    @Test
    void DocsSayGetAlertOnlyClientTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("`alertreadonlycontextpage` depends only on a getalert-only client");
        assertThat(lower).contains("existing get `/api/v1/alerts/{alertid}` response remains the read source");
    }

    @Test
    void DocsSayNoBackendEndpointTest() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("does not add a new backend endpoint");
        assertThat(lower).contains("does not introduce a second alert read api");
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
        assertThat(lower).contains("backend `alert_read` authorization remains authoritative");
    }

    @Test
    void DocsSayAlertDetailsPageNoLongerHasReadOnlyContext() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("`alertdetailspage` no longer accepts `readonlycontext`");
        assertThat(lower).contains("fully removes suspicioustransaction read-only bridge mode from `alertdetailspage`");
    }

    @Test
    void DocsSayAlertReadOnlyContextPageIsOnlySuspiciousBridgeComponent() throws Exception {
        String lower = Files.readString(docPath()).toLowerCase(Locale.ROOT);

        assertThat(lower).contains("`alertreadonlycontextpage` is the only component for suspicioustransaction linked-alert read-only context");
        assertThat(lower).contains("`alertreadonlycontextpage` depends only on a getalert-only client");
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
