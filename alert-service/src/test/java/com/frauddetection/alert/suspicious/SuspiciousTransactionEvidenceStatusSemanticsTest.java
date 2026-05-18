package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.evidence.EvidenceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.availableEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.errorEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.partialEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.unavailableEvidence;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionEvidenceStatusSemanticsTest {

    @Test
    void availableEvidenceProducesAvailableMetadataStatus() {
        var document = project(List.of(availableEvidence()));

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
    }

    @Test
    void emptyEvidenceForAlertWorthyEventIsPartialNotAvailable() {
        var document = project(List.of());

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
    }

    @Test
    void diagnosticOnlyEvidenceDoesNotDefaultToAvailable() {
        var partial = project(List.of(partialEvidence()));
        var unavailable = project(List.of(unavailableEvidence()));
        var error = project(List.of(errorEvidence()));

        assertThat(partial.getEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(unavailable.getEvidenceStatus()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(error.getEvidenceStatus()).isEqualTo(EvidenceStatus.ERROR);
    }

    private SuspiciousTransactionDocument project(List<com.frauddetection.common.events.evidence.ScoringEvidenceItem> evidence) {
        var event = SuspiciousTransactionTestSupport.event(
                "event-" + evidence.hashCode(),
                "txn-" + evidence.hashCode(),
                true,
                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                evidence
        );
        return service(inMemoryRepository(), metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .projectOrUpdate(event, null)
                .orElseThrow();
    }
}
