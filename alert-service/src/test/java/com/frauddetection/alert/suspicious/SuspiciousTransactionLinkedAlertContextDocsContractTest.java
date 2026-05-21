package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionLinkedAlertContextDocsContractTest {

    private static final Path BRIDGE_DOCS = Path.of("../docs/product/alert_read_only_detail_bridge.md");
    private static final Path UI_DOCS = Path.of("../docs/product/suspicious_transaction_internal_ui.md");

    @Test
    void DocsMentionBackendRelationshipValidationTest() throws Exception {
        String docs = docs();

        assertThat(docs)
                .contains("backend relationship validation is authoritative")
                .contains("derives `linkedAlertId`")
                .contains("validates the relationship")
                .contains("fails closed");
    }

    @Test
    void DocsMentionClientCannotPassAlertIdTest() throws Exception {
        assertThat(docs())
                .contains("Clients cannot pass `alertId`")
                .contains("The backend must not accept `alertId`");
    }

    @Test
    void DocsMentionBothAuthoritiesRequiredTest() throws Exception {
        assertThat(docs())
                .contains("requires both `SUSPICIOUS_TRANSACTION_READ` and `ALERT_READ`")
                .contains("The endpoint requires both `SUSPICIOUS_TRANSACTION_READ` and `ALERT_READ`");
    }

    @Test
    void DocsMentionMinimalReadOnlyResponseTest() throws Exception {
        assertThat(docs())
                .contains("minimal alert read-model context")
                .contains("minimal read-only context")
                .contains("alertId, transactionId, customerId");
    }

    @Test
    void DocsMentionNoWorkflowNoDecisionNoFinalOutcomeTest() throws Exception {
        assertThat(docs())
                .contains("does not add workflow")
                .contains("does not submit analyst decisions")
                .contains("not a final outcome");
    }

    @Test
    void DocsMentionNoRawIdsInLogsMetricsTest() throws Exception {
        assertThat(docs())
                .contains("bounded outcome labels")
                .contains("must not log raw identifiers")
                .contains("must not")
                .contains("idempotency keys");
    }

    @Test
    void DocsAllowSourceResourceIdOnlyUnderSensitiveReadAuditPolicyTest() throws Exception {
        assertThat(docs())
                .contains("source SuspiciousTransaction resourceId")
                .contains("existing sensitive-read audit target policy");
        assertThat(docs().toLowerCase(Locale.ROOT))
                .contains("metrics and ordinary logs must not contain raw identifiers");
    }

    @Test
    void DocsForbidRawLinkedAlertIdentifiersInAuditMetadataTest() throws Exception {
        assertThat(docs())
                .contains("must not record raw alertId")
                .contains("must not record raw linkedAlertId")
                .contains("must not record raw customerId")
                .contains("raw accountId")
                .contains("raw transactionId")
                .contains("must not record raw correlationId")
                .contains("raw scoreDecisionId")
                .contains("raw query string")
                .contains("raw request path")
                .contains("must not record raw exception message")
                .contains("response body");
    }

    @Test
    void DocsExplainScoreDecisionIdLineageSourceTest() throws Exception {
        assertThat(docs())
                .contains("scoreDecisionId is sourced from SuspiciousTransaction")
                .contains("scoreDecisionId is lineage metadata for the source suspicious signal")
                .contains("not used for alert-side compatibility unless the alert read model exposes an equivalent field")
                .contains("Relationship validation currently uses alertId, transactionId, customerId, and correlationId where available");
    }

    @Test
    void DocsDoNotClaimScoreDecisionIdAlertSideValidationTest() throws Exception {
        assertThat(docs())
                .doesNotContain("scoreDecisionId is validated against alert")
                .doesNotContain("alert-side scoreDecisionId validation")
                .doesNotContain("scoreDecisionId relationship validation");
    }

    @Test
    void DocsMentionClientsMustEvaluateStateTest() throws Exception {
        assertThat(docs())
                .contains("Clients must evaluate state")
                .contains("HTTP 200 does not imply linked alert context is available")
                .contains("TEMPORARILY_UNAVAILABLE is a degraded read state");
    }

    @Test
    void DocsMentionHttp200DoesNotMeanAvailableTest() throws Exception {
        assertThat(docs())
                .contains("HTTP 200 does not imply LINKED_ALERT_AVAILABLE")
                .contains("UI/client must not render alert context fields for TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void DocsMentionErrorMetricForTemporaryUnavailableTest() throws Exception {
        assertThat(docs())
                .contains("fraud.suspicious_transaction.linked_alert.read{outcome=\"error\"}");
    }

    @Test
    void DocsMentionUpdatedAtIsNullableTest() throws Exception {
        assertThat(docs())
                .contains("updatedAt is nullable")
                .contains("Clients must not assume it is present")
                .contains("updatedAt only when the alert read model exposes a reliable updated timestamp");
    }

    @Test
    void DocsForbidFakeUpdatedTimestampTest() throws Exception {
        assertThat(docs())
                .contains("createdAt must not be treated as update time")
                .contains("must not synthesize fake updatedAt values");
    }

    @Test
    void DocsMentionUiUsesBackendResolverTest() throws Exception {
        assertThat(docs())
                .contains("The SuspiciousTransaction linked-alert UI uses the backend linked-alert resolver")
                .contains("The SuspiciousTransaction linked-alert UI uses the backend relationship resolver")
                .contains("AlertReadOnlyContextPage calls GET `/internal/suspicious-transactions/{suspiciousTransactionId}/linked-alert`");
    }

    @Test
    void DocsMentionFdp71ContractOwnershipTest() throws Exception {
        assertThat(docs())
                .contains("WorkspaceDetailRouter` owns route/source readiness")
                .contains("AlertReadOnlyContextPage` owns resolver state rendering")
                .contains("The backend owns linked-alert relationship validation")
                .contains("No frontend relationship validation is a source of truth");
    }

    @Test
    void DocsMentionUiNoLongerUsesGeneralAlertLookupTest() throws Exception {
        assertThat(docs())
                .contains("does not call GET `/api/v1/alerts/{alertId}`")
                .contains("The frontend sends `suspiciousTransactionId` only")
                .contains("does not send `alertId` or `linkedAlertId` to the resolver");
    }

    @Test
    void DocsDoNotClaimLegalProofOrConfirmedFraudTest() throws Exception {
        String lower = docs().toLowerCase(Locale.ROOT);

        assertThat(lower)
                .doesNotContain("is legal proof")
                .doesNotContain("is confirmed fraud")
                .doesNotContain("confirmed fraud context")
                .doesNotContain("legal proof context");
    }

    private String docs() throws Exception {
        return Files.readString(BRIDGE_DOCS) + "\n" + Files.readString(UI_DOCS);
    }
}
