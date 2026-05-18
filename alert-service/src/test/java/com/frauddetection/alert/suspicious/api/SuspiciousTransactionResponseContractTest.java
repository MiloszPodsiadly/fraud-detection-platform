package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.suspicious.DetectionSource;
import com.frauddetection.alert.suspicious.SuspiciousTransactionStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionResponseContractTest {

    @Test
    void mapsAllowedReadModelFieldsAndDefensivelyCopiesReasonCodes() {
        ArrayList<String> reasons = new ArrayList<>(List.of("HIGH_AMOUNT"));
        SuspiciousTransactionResponse response = new SuspiciousTransactionResponse(
                "suspicious-1",
                "txn-1",
                "event-1",
                "corr-1",
                "customer-1",
                "account-1",
                0.91,
                RiskLevel.HIGH,
                DetectionSource.RULE_ENGINE,
                reasons,
                EvidenceStatus.PARTIAL,
                2,
                "PARTIAL_METADATA",
                "alert-1",
                SuspiciousTransactionStatus.ALERT_CREATED,
                Instant.parse("2026-05-18T10:00:00Z"),
                Instant.parse("2026-05-18T10:01:00Z"),
                Instant.parse("2026-05-18T10:02:00Z"),
                "decision-1",
                "RULE_BASED",
                "model-a",
                "v1"
        );
        reasons.add("MUTATED");

        assertThat(response.suspiciousTransactionId()).isEqualTo("suspicious-1");
        assertThat(response.transactionId()).isEqualTo("txn-1");
        assertThat(response.sourceEventId()).isEqualTo("event-1");
        assertThat(response.evidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(response.reasonCodes()).containsExactly("HIGH_AMOUNT");
    }

    @Test
    void nullReasonCodesBecomeEmptyList() {
        SuspiciousTransactionResponse response = minimalResponse(null);

        assertThat(response.reasonCodes()).isEmpty();
    }

    @Test
    void forbiddenFraudVerdictAndPayloadFieldsAreAbsent() {
        assertThat(recordFieldNames()).doesNotContain(
                "fraudConfirmed",
                "verdict",
                "finalOutcome",
                "analystDecision",
                "legalProof",
                "caseDecision",
                "evidenceSnapshot",
                "rawModelPayload",
                "rawEventPayload",
                "confirmedFraud",
                "fraudVerdict"
        );
        assertThat(SuspiciousTransactionResponse.class.getSimpleName()).doesNotContain("FraudTransaction");
    }

    static SuspiciousTransactionResponse minimalResponse(List<String> reasonCodes) {
        return new SuspiciousTransactionResponse(
                "suspicious-1",
                "txn-1",
                "event-1",
                "corr-1",
                "customer-1",
                "account-1",
                0.91,
                RiskLevel.HIGH,
                DetectionSource.RULE_ENGINE,
                reasonCodes,
                EvidenceStatus.AVAILABLE,
                1,
                "AVAILABLE_METADATA",
                null,
                SuspiciousTransactionStatus.NEW,
                Instant.parse("2026-05-18T10:00:00Z"),
                Instant.parse("2026-05-18T10:01:00Z"),
                Instant.parse("2026-05-18T10:02:00Z"),
                "decision-1",
                "RULE_BASED",
                "model-a",
                "v1"
        );
    }

    static List<String> recordFieldNames() {
        return Arrays.stream(SuspiciousTransactionResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
