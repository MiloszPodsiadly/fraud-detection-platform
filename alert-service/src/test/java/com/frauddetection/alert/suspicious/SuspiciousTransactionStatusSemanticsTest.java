package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionStatusSemanticsTest {

    @Test
    void containsExactlyReadModelStatuses() {
        assertThat(SuspiciousTransactionStatus.values())
                .containsExactly(
                        SuspiciousTransactionStatus.NEW,
                        SuspiciousTransactionStatus.ALERT_CREATED,
                        SuspiciousTransactionStatus.LEGACY_IMPORTED
                );
    }

    @Test
    void doesNotContainAnalystOrOutcomeStatuses() {
        assertThat(Arrays.stream(SuspiciousTransactionStatus.values()).map(Enum::name))
                .doesNotContain("DISMISSED", "CONFIRMED_RELEVANT", "CONFIRMED_LEGITIMATE", "LINKED_TO_CASE")
                .noneMatch(name -> name.contains("FRAUD")
                        || name.contains("VERDICT")
                        || name.contains("FINAL")
                        || name.contains("ANALYST"));
    }
}
