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
                        AnalystAuthority.FRAUD_CASE_UPDATE
                );
    }

    @Test
    void analystShouldSubmitDecisionsButNotUpdateFraudCases() {
        assertThat(AnalystRole.ANALYST.authorities())
                .contains(AnalystAuthority.ALERT_DECISION_SUBMIT)
                .doesNotContain(AnalystAuthority.FRAUD_CASE_UPDATE);
    }

    @Test
    void reviewerShouldHaveCaseUpdateAuthority() {
        assertThat(AnalystRole.REVIEWER.authorities())
                .contains(AnalystAuthority.FRAUD_CASE_UPDATE);
    }

    @Test
    void fraudOpsAdminShouldHaveAllAuthorities() {
        assertThat(AnalystRole.FRAUD_OPS_ADMIN.authorities())
                .containsExactlyInAnyOrderElementsOf(AnalystAuthority.ALL);
    }
}
