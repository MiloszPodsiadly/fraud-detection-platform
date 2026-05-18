package com.frauddetection.alert.suspicious;

import com.frauddetection.alert.evidence.EvidenceStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.availableEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.errorEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.legacyEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.notApplicableEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.partialEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.unavailableEvidence;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.withCorrelationId;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionEvidenceStatusSemanticsTest {

    @Test
    void allAvailableEvidenceProducesAvailableMetadataStatus() {
        var document = project(List.of(availableEvidence(), availableEvidence()));

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("AVAILABLE_METADATA");
    }

    @Test
    void mixedAvailableAndErrorDoesNotProduceAvailableMetadataStatus() {
        var document = project(List.of(availableEvidence(), errorEvidence()));

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.ERROR);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("ERROR_METADATA");
    }

    @Test
    void mixedAvailableAndPartialDoesNotProduceAvailableMetadataStatus() {
        var document = project(List.of(availableEvidence(), partialEvidence()));

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("PARTIAL_METADATA");
    }

    @Test
    void mixedAvailableAndLegacyDoesNotProduceAvailableMetadataStatus() {
        var document = project(List.of(availableEvidence(), legacyEvidence()));

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("PARTIAL_METADATA");
    }

    @Test
    void mixedAvailableAndUnavailableDoesNotProduceAvailableMetadataStatus() {
        var document = project(List.of(availableEvidence(), unavailableEvidence()));

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("PARTIAL_METADATA");
    }

    @Test
    void mixedAvailableAndNotApplicableDoesNotProduceAvailableMetadataStatus() {
        var document = project(List.of(availableEvidence(), notApplicableEvidence()));

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("PARTIAL_METADATA");
    }

    @Test
    void nullScoringEvidenceItemDoesNotProduceAvailableMetadataStatus() {
        var projection = service(inMemoryRepository(), metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var status = projection.evidenceStatus(Arrays.asList(availableEvidence(), null));

        assertThat(status).isEqualTo(EvidenceStatus.ERROR);
        assertThat(projection.evidenceProjectionState(status)).isEqualTo("ERROR_METADATA");
    }

    @Test
    void onlyUnavailableEvidenceProducesUnavailableMetadataStatus() {
        var document = project(List.of(unavailableEvidence(), notApplicableEvidence()));

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("UNAVAILABLE_METADATA");
    }

    @Test
    void emptyScoringEvidenceProducesPartialMetadataStatus() {
        var document = project(List.of());

        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("PARTIAL_METADATA");
    }

    @Test
    void missingCorrelationIdCreatesPartialLineageMetadata() {
        var event = withCorrelationId(SuspiciousTransactionTestSupport.event(
                "event-missing-correlation",
                "txn-missing-correlation",
                true,
                com.frauddetection.common.events.enums.RiskLevel.HIGH,
                List.of(availableEvidence())
        ), null);

        var document = service(inMemoryRepository(), metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .projectOrUpdate(event, null)
                .orElseThrow();

        assertThat(document.getTransactionId()).isEqualTo(event.transactionId());
        assertThat(document.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(document.getCorrelationId()).isNull();
        assertThat(document.getEvidenceStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(document.getEvidenceProjectionState()).isEqualTo("PARTIAL_METADATA");
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
