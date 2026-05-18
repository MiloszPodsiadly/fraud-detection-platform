package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionApiDoesNotExposeFullEvidenceSnapshotTest {

    @Test
    void responseDtoLacksEvidenceSnapshotAndRawPayloadFields() {
        assertThat(SuspiciousTransactionResponseContractTest.recordFieldNames()).doesNotContain(
                "evidenceSnapshot",
                "rawModelPayload",
                "rawEventPayload",
                "rawScoringPayload",
                "scoringPayload"
        );
    }
}
