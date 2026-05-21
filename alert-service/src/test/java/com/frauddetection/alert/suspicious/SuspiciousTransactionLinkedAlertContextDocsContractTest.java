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
                .contains("must not record raw correlationId")
                .contains("must not record raw exception message");
    }

    @Test
    void DocsExplainScoreDecisionIdLineageSourceTest() throws Exception {
        assertThat(docs())
                .contains("scoreDecisionId is sourced from SuspiciousTransaction")
                .contains("not used for alert-side compatibility unless the alert read model exposes an equivalent field")
                .contains("Relationship validation currently uses alertId, transactionId, customerId, and correlationId where available");
    }

    @Test
    void DocsMentionClientsMustEvaluateStateTest() throws Exception {
        assertThat(docs())
                .contains("Clients must evaluate state")
                .contains("HTTP 200 does not imply LINKED_ALERT_AVAILABLE")
                .contains("TEMPORARILY_UNAVAILABLE is a degraded read state");
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
