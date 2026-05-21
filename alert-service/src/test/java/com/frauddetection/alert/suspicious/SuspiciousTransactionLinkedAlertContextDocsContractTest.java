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
