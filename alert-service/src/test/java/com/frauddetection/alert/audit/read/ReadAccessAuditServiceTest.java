package com.frauddetection.alert.audit.read;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadAccessAuditServiceTest {

    private final ReadAccessAuditRepository repository = mock(ReadAccessAuditRepository.class);
    private final CurrentAnalystUser currentAnalystUser = mock(CurrentAnalystUser.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ReadAccessAuditService service = new ReadAccessAuditService(
            repository,
            currentAnalystUser,
            new AlertServiceMetrics(meterRegistry)
    );

    @Test
    void shouldPersistBoundedReadAccessAuditWithoutSensitiveFields() {
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "analyst-1",
                Set.of(AnalystRole.FRAUD_OPS_ADMIN),
                AnalystRole.FRAUD_OPS_ADMIN.authorities()
        )));
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(
                ReadAccessEndpointCategory.SCORED_TRANSACTION_SEARCH,
                ReadAccessResourceType.SCORED_TRANSACTION,
                null,
                "abc123",
                0,
                100
        );

        service.audit(target, ReadAccessAuditOutcome.SUCCESS, 150, "corr-1");

        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository).save(captor.capture());
        ReadAccessAuditEventDocument document = captor.getValue();
        assertThat(document.action()).isEqualTo(ReadAccessAuditAction.READ);
        assertThat(document.resultCount()).isEqualTo(100);
        assertThat(document.createdAt()).isEqualTo(document.occurredAt());
        assertThat(document.actorRoles()).containsExactly("FRAUD_OPS_ADMIN");
        assertThat(document.toString())
                .doesNotContain("request", "response", "payload", "customerId", "accountId", "cardNumber", "token", "stack");
        assertThat(meterRegistry.get("fraud_platform_read_access_audit_events_persisted_total")
                .tag("endpoint_category", "scored_transaction_search")
                .tag("outcome", "success")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void shouldNotThrowWhenPersistenceFails() {
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "analyst-1",
                Set.of(AnalystRole.FRAUD_OPS_ADMIN),
                AnalystRole.FRAUD_OPS_ADMIN.authorities()
        )));
        doThrow(new DataAccessResourceFailureException("mongo unavailable"))
                .when(repository).save(org.mockito.ArgumentMatchers.any());
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(
                ReadAccessEndpointCategory.GOVERNANCE_ADVISORY_ANALYTICS,
                ReadAccessResourceType.GOVERNANCE_ADVISORY_ANALYTICS,
                null,
                null,
                null,
                null
        );

        service.audit(target, ReadAccessAuditOutcome.SUCCESS, 1, null);

        assertThat(meterRegistry.get("fraud_platform_read_access_audit_persistence_failures_total")
                .tag("endpoint_category", "governance_advisory_analytics")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void shouldThrowWhenPersistenceFailsAndCallerRequiresFailClosedRead() {
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "ops-admin",
                Set.of(AnalystRole.FRAUD_OPS_ADMIN),
                AnalystRole.FRAUD_OPS_ADMIN.authorities()
        )));
        doThrow(new DataAccessResourceFailureException("mongo unavailable"))
                .when(repository).save(org.mockito.ArgumentMatchers.any());
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(
                ReadAccessEndpointCategory.PREVIEW_TRUST_INCIDENT_SIGNALS,
                ReadAccessResourceType.TRUST_INCIDENT_SIGNAL,
                null,
                null,
                null,
                1
        );

        assertThatThrownBy(() -> service.auditOrThrow(target, ReadAccessAuditOutcome.SUCCESS, 1, "corr-1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(meterRegistry.get("fraud_platform_read_access_audit_persistence_failures_total")
                .tag("endpoint_category", "preview_trust_incident_signals")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void shouldPersistUnknownActorAndEmitAnomalyMetricWhenPrincipalIsMissing() {
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.empty());
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(
                ReadAccessEndpointCategory.ALERT_DETAIL,
                ReadAccessResourceType.ALERT,
                "alert-1",
                null,
                null,
                null
        );

        service.audit(target, ReadAccessAuditOutcome.SUCCESS, 1, "corr-1");

        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo("unknown");
        assertThat(meterRegistry.get("fraud_read_access_audit_actor_missing_total")
                .tag("endpoint_category", "alert_detail")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
