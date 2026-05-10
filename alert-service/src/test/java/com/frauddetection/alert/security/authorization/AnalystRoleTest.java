package com.frauddetection.alert.security.authorization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystRoleTest {

    @Test
    void readOnlyAnalystShouldNotHaveWriteAuthorities() {
        assertThat(AnalystRole.READ_ONLY_ANALYST.authorities())
                .contains(
                        AnalystAuthority.ALERT_READ,
                        AnalystAuthority.ASSISTANT_SUMMARY_READ,
                        AnalystAuthority.FRAUD_CASE_READ,
                        AnalystAuthority.TRANSACTION_MONITOR_READ
                )
                .doesNotContain(
                        AnalystAuthority.ALERT_DECISION_SUBMIT,
                        AnalystAuthority.FRAUD_CASE_AUDIT_READ,
                        AnalystAuthority.FRAUD_CASE_UPDATE,
                        AnalystAuthority.GOVERNANCE_ADVISORY_AUDIT_WRITE,
                        AnalystAuthority.AUDIT_READ,
                        AnalystAuthority.AUDIT_VERIFY,
                        AnalystAuthority.AUDIT_EXPORT,
                        AnalystAuthority.AUDIT_DEGRADATION_RESOLVE,
                        AnalystAuthority.DECISION_OUTBOX_RECONCILE
                );
    }

    @Test
    void analystShouldSubmitDecisionsButNotUpdateFraudCases() {
        assertThat(AnalystRole.ANALYST.authorities())
                .contains(
                        AnalystAuthority.ALERT_DECISION_SUBMIT,
                        AnalystAuthority.GOVERNANCE_ADVISORY_AUDIT_WRITE
                )
                .doesNotContain(
                        AnalystAuthority.FRAUD_CASE_UPDATE,
                        AnalystAuthority.FRAUD_CASE_AUDIT_READ,
                        AnalystAuthority.AUDIT_READ,
                        AnalystAuthority.AUDIT_VERIFY,
                        AnalystAuthority.AUDIT_EXPORT,
                        AnalystAuthority.AUDIT_DEGRADATION_RESOLVE,
                        AnalystAuthority.DECISION_OUTBOX_RECONCILE
                );
    }

    @Test
    void reviewerShouldHaveCaseUpdateAuthority() {
        assertThat(AnalystRole.REVIEWER.authorities())
                .contains(
                        AnalystAuthority.FRAUD_CASE_UPDATE,
                        AnalystAuthority.FRAUD_CASE_AUDIT_READ,
                        AnalystAuthority.GOVERNANCE_ADVISORY_AUDIT_WRITE
                )
                .doesNotContain(
                        AnalystAuthority.AUDIT_READ,
                        AnalystAuthority.AUDIT_VERIFY,
                        AnalystAuthority.AUDIT_EXPORT,
                        AnalystAuthority.AUDIT_DEGRADATION_RESOLVE,
                        AnalystAuthority.DECISION_OUTBOX_RECONCILE
                );
    }

    @Test
    void fraudOpsAdminShouldHaveAllAuthorities() {
        assertThat(AnalystRole.FRAUD_OPS_ADMIN.authorities())
                .containsExactlyInAnyOrderElementsOf(AnalystAuthority.ALL);
    }
}
