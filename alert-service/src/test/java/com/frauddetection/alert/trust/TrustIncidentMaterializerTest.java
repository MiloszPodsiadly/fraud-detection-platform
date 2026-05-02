package com.frauddetection.alert.trust;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustIncidentMaterializerTest {

    @Test
    void shouldRecordMaterializedMetricForNewIncident() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        TrustIncidentDocument created = incident(Instant.parse("2026-05-02T10:00:00Z"), 1L);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TrustIncidentDocument.class)))
                .thenReturn(created);

        new TrustIncidentMaterializer(repository, mongoTemplate, new TrustIncidentPolicy(), new AlertServiceMetrics(registry))
                .materialize(List.of(signal()));

        assertThat(registry.counter(
                "trust_incident_materialized_total",
                "type", "OUTBOX_TERMINAL_FAILURE",
                "severity", "CRITICAL",
                "result", "CREATED"
        ).count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRecordDedupedMetricForExistingIncident() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        TrustIncidentDocument existing = incident(Instant.parse("2026-05-02T09:00:00Z"), 2L);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TrustIncidentDocument.class)))
                .thenReturn(existing);

        new TrustIncidentMaterializer(repository, mongoTemplate, new TrustIncidentPolicy(), new AlertServiceMetrics(registry))
                .materialize(List.of(signal()));

        assertThat(registry.counter(
                "trust_incident_deduped_total",
                "type", "OUTBOX_TERMINAL_FAILURE",
                "severity", "CRITICAL"
        ).count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRecordFailureMetricWhenMaterializationFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrustIncidentRepository repository = mock(TrustIncidentRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TrustIncidentDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));
        when(repository.findByActiveDedupeKey(any())).thenReturn(Optional.empty());

        TrustIncidentMaterializer materializer = new TrustIncidentMaterializer(
                repository,
                mongoTemplate,
                new TrustIncidentPolicy(),
                new AlertServiceMetrics(registry)
        );

        assertThatThrownBy(() -> materializer.materialize(List.of(signal())))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(registry.counter(
                "trust_incident_materialization_failed_total",
                "reason", "PERSISTENCE_UNAVAILABLE"
        ).count()).isEqualTo(1.0d);
    }

    private TrustSignal signal() {
        return new TrustSignal(
                "OUTBOX_TERMINAL_FAILURE",
                TrustIncidentSeverity.CRITICAL,
                "transactional_outbox",
                "status=FAILED_TERMINAL",
                List.of("outbox:event-1")
        );
    }

    private TrustIncidentDocument incident(Instant createdAt, long occurrenceCount) {
        TrustIncidentDocument document = new TrustIncidentDocument();
        document.setIncidentId("incident-1");
        document.setType("OUTBOX_TERMINAL_FAILURE");
        document.setSeverity(TrustIncidentSeverity.CRITICAL);
        document.setSource("transactional_outbox");
        document.setFingerprint("fingerprint");
        document.setStatus(TrustIncidentStatus.OPEN);
        document.setCreatedAt(createdAt);
        document.setOccurrenceCount(occurrenceCount);
        return document;
    }
}
