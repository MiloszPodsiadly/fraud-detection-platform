package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentAuditEventPublisherTest {

    @Test
    void shouldPersistAppendOnlyAuditDocumentWithoutPayloadData() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                new AlertServiceMetrics(meterRegistry)
        );
        AuditEvent event = new AuditEvent(
                new AuditActor("analyst-1", Set.of("ANALYST"), Set.of("alert:decision:submit")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                Instant.parse("2026-04-23T10:00:00Z"),
                "corr-1",
                AuditOutcome.SUCCESS,
                null
        );

        publisher.publish(event);

        ArgumentCaptor<AuditEventDocument> documentCaptor = ArgumentCaptor.forClass(AuditEventDocument.class);
        verify(repository).save(documentCaptor.capture());
        AuditEventDocument document = documentCaptor.getValue();
        assertThat(document.auditId()).isNotBlank();
        assertThat(document.eventType()).isEqualTo(AuditAction.SUBMIT_ANALYST_DECISION);
        assertThat(document.actorId()).isEqualTo("analyst-1");
        assertThat(document.actorDisplayName()).isEqualTo("analyst-1");
        assertThat(document.actorRoles()).containsExactly("ANALYST");
        assertThat(document.actorAuthorities()).containsExactly("alert:decision:submit");
        assertThat(document.action()).isEqualTo(AuditAction.SUBMIT_ANALYST_DECISION);
        assertThat(document.resourceType()).isEqualTo(AuditResourceType.ALERT);
        assertThat(document.resourceId()).isEqualTo("alert-1");
        assertThat(document.createdAt()).isEqualTo(Instant.parse("2026-04-23T10:00:00Z"));
        assertThat(document.correlationId()).isEqualTo("corr-1");
        assertThat(document.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(document.failureReason()).isNull();
        assertThat(java.util.Arrays.stream(document.getClass().getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("requestBody", "responseBody", "transactionPayload", "featureVector", "customerId", "accountId", "cardNumber");
        assertThat(meterRegistry.get("fraud_platform_audit_events_persisted_total")
                .tags("event_type", "submit_analyst_decision", "outcome", "success")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldFailClearlyWhenAuditPersistenceIsUnavailable() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("mongo down"));
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                new AlertServiceMetrics(meterRegistry)
        );

        assertThatThrownBy(() -> publisher.publish(new AuditEvent(
                new AuditActor("analyst-1", Set.of("ANALYST"), Set.of()),
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                Instant.parse("2026-04-23T10:00:00Z"),
                null,
                AuditOutcome.SUCCESS,
                null
        ))).isInstanceOf(AuditPersistenceUnavailableException.class)
                .extracting(Throwable::getMessage)
                .isNull();

        assertThat(meterRegistry.get("fraud_platform_audit_persistence_failures_total")
                .tag("event_type", "update_fraud_case")
                .counter()
                .count()).isEqualTo(1.0d);
    }
}
