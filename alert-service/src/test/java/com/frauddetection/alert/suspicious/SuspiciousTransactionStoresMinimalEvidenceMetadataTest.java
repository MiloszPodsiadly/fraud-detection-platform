package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.alertWorthyEvent;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.inMemoryRepository;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.metrics;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.service;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionTestSupport.withScoreDetails;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionStoresMinimalEvidenceMetadataTest {

    @Test
    void storesCountStatusProjectionStateAndScoringMetadataOnly() {
        var event = withScoreDetails(alertWorthyEvent(), Map.of("scoreDecisionId", "score-decision-1"));

        var document = service(inMemoryRepository(), metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .projectOrUpdate(event, null)
                .orElseThrow();

        assertThat(document.getEvidenceSnapshotItemCount()).isEqualTo(event.scoringEvidence().size());
        assertThat(document.getEvidenceProjectionState()).isEqualTo("AVAILABLE_METADATA");
        assertThat(document.getReasonCodes()).containsExactlyElementsOf(event.reasonCodes());
        assertThat(document.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(document.getCorrelationId()).isEqualTo(event.correlationId());
        assertThat(document.getScoreDecisionId()).isEqualTo("score-decision-1");
        assertThat(document.getScoringStrategy()).isEqualTo(event.scoringStrategy());
        assertThat(document.getModelName()).isEqualTo(event.modelName());
        assertThat(document.getModelVersion()).isEqualTo(event.modelVersion());
    }

    @Test
    void scoreDecisionIdIsNotInferredFromSourceEventIdWhenMetadataIsAbsent() {
        var event = alertWorthyEvent();

        var document = service(inMemoryRepository(), metrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .projectOrUpdate(event, null)
                .orElseThrow();

        assertThat(document.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(document.getScoreDecisionId()).isNull();
    }
}
