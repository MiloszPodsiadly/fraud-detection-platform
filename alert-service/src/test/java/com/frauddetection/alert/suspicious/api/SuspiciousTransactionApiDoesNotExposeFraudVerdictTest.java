package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionApiDoesNotExposeFraudVerdictTest {

    @Test
    void responseDtoLacksFraudVerdictFields() {
        assertThat(SuspiciousTransactionResponseContractTest.recordFieldNames()).doesNotContain(
                "fraudConfirmed",
                "verdict",
                "finalOutcome",
                "analystDecision",
                "legalProof",
                "caseDecision",
                "confirmedFraud",
                "fraudVerdict"
        );
    }
}
