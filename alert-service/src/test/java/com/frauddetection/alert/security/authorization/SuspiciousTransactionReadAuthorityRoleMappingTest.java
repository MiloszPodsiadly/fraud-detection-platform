package com.frauddetection.alert.security.authorization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionReadAuthorityRoleMappingTest {

    @Test
    void viewerDoesNotReceiveSuspiciousTransactionRead() {
        assertThat(AnalystRole.READ_ONLY_ANALYST.authorities())
                .doesNotContain(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ);
    }

    @Test
    void analystReceivesSuspiciousTransactionRead() {
        assertThat(AnalystRole.ANALYST.authorities())
                .contains(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ);
    }

    @Test
    void reviewerReceivesSuspiciousTransactionRead() {
        assertThat(AnalystRole.REVIEWER.authorities())
                .contains(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ);
    }

    @Test
    void adminReceivesSuspiciousTransactionReadThroughAllAuthorities() {
        assertThat(AnalystRole.FRAUD_OPS_ADMIN.authorities())
                .contains(AnalystAuthority.SUSPICIOUS_TRANSACTION_READ)
                .containsExactlyInAnyOrderElementsOf(AnalystAuthority.ALL);
    }
}
