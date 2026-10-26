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
import static org.mockito.Mockito.never;
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
                "query",
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
        assertThat(document.filterBucket()).isEqualTo("query");
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
                AnalystRole.FRAUD_OPS_ADMIN.authorities(),
                "https://issuer.example#ops-admin"
        )));
        doThrow(new DataAccessResourceFailureException("mongo unavailable"))
                .when(repository).save(org.mockito.ArgumentMatchers.any());
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(
                ReadAccessEndpointCategory.PREVIEW_TRUST_INCIDENT_SIGNALS,
                ReadAccessResourceType.TRUST_INCIDENT_SIGNAL,
                null,
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
    void trustedReadAuditActorUsesCanonicalIssuerAndSubjectIdentity() {
        ReadAccessAuditTarget target = simpleTarget();
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "shared-subject", Set.of(), Set.of(), "https://issuer-a.example#shared-subject")));
        service.auditOrThrow(target, ReadAccessAuditOutcome.SUCCESS, 1, "corr-1");
        ArgumentCaptor<ReadAccessAuditEventDocument> first = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository).save(first.capture());
        assertThat(first.getValue().actorId())
                .isEqualTo(com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor.identityId(
                        "https://issuer-a.example", "shared-subject"));

        org.mockito.Mockito.reset(repository);
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "shared-subject", Set.of(), Set.of(), "https://issuer-b.example#shared-subject")));
        service.auditOrThrow(target, ReadAccessAuditOutcome.SUCCESS, 1, "corr-2");
        ArgumentCaptor<ReadAccessAuditEventDocument> second = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository).save(second.capture());
        assertThat(second.getValue().actorId()).isNotEqualTo(first.getValue().actorId());
    }

    @Test
    void repeatedTrustedReadAuditActorIsStableAndDemoActorIsExplicitlyLocal() {
        ReadAccessAuditTarget target = simpleTarget();
        AnalystPrincipal trusted = new AnalystPrincipal(
                "display-name", Set.of(), Set.of(), "https://issuer.example#stable-subject");
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(trusted));
        service.audit(target, ReadAccessAuditOutcome.SUCCESS, 1, "corr-1");
        service.audit(target, ReadAccessAuditOutcome.SUCCESS, 1, "corr-2");
        ArgumentCaptor<ReadAccessAuditEventDocument> trustedEvents = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository, org.mockito.Mockito.times(2)).save(trustedEvents.capture());
        assertThat(trustedEvents.getAllValues()).extracting(ReadAccessAuditEventDocument::actorId)
                .containsOnly(com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor.identityId(
                        "https://issuer.example", "stable-subject"));

        org.mockito.Mockito.reset(repository);
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "demo-user", Set.of(), Set.of(), null,
                "local:" + com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor.identityId(
                        "demo-auth", "ZGVtby11c2Vy").substring("oidc:".length()))));
        service.auditOrThrow(target, ReadAccessAuditOutcome.SUCCESS, 1, "corr-3");
        ArgumentCaptor<ReadAccessAuditEventDocument> demoEvent = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository).save(demoEvent.capture());
        assertThat(demoEvent.getValue().actorId()).startsWith("local:").doesNotContain("demo-user");
    }

    @Test
    void metricsFailureAfterSuccessfulSaveDoesNotFailOrRepeatTheDurableAudit() {
        AlertServiceMetrics failingMetrics = mock(AlertServiceMetrics.class);
        ReadAccessAuditService audited = new ReadAccessAuditService(repository, currentAnalystUser, failingMetrics);
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "ops-admin", Set.of(), Set.of(), "https://issuer.example#ops-admin")));
        doThrow(new IllegalStateException("metrics registry unavailable"))
                .when(failingMetrics).recordReadAccessAuditPersisted(
                        ReadAccessEndpointCategory.ALERT_DETAIL, ReadAccessAuditOutcome.SUCCESS);

        audited.auditOrThrow(simpleTarget(), ReadAccessAuditOutcome.SUCCESS, 1, "corr-metrics");

        verify(repository, org.mockito.Mockito.times(1)).save(org.mockito.ArgumentMatchers.any());
        verify(failingMetrics, org.mockito.Mockito.never()).recordReadAccessAuditPersistenceFailure(
                ReadAccessEndpointCategory.ALERT_DETAIL);
    }

    @Test
    void persistenceFailureRemainsAuthoritativeWhenFailureMetricAlsoThrows() {
        AlertServiceMetrics failingMetrics = mock(AlertServiceMetrics.class);
        ReadAccessAuditService audited = new ReadAccessAuditService(repository, currentAnalystUser, failingMetrics);
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "ops-admin", Set.of(), Set.of(), "https://issuer.example#ops-admin")));
        DataAccessResourceFailureException mongoFailure = new DataAccessResourceFailureException("mongo unavailable");
        doThrow(mongoFailure).when(repository).save(org.mockito.ArgumentMatchers.any());
        doThrow(new IllegalStateException("metrics registry unavailable"))
                .when(failingMetrics).recordReadAccessAuditPersistenceFailure(ReadAccessEndpointCategory.ALERT_DETAIL);

        assertThatThrownBy(() -> audited.auditOrThrow(simpleTarget(), ReadAccessAuditOutcome.SUCCESS, 1, "corr-metrics"))
                .isSameAs(mongoFailure);
        verify(repository, org.mockito.Mockito.times(1)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingStrictActorRemainsRejectedWhenActorMetricThrows() {
        AlertServiceMetrics failingMetrics = mock(AlertServiceMetrics.class);
        ReadAccessAuditService audited = new ReadAccessAuditService(repository, currentAnalystUser, failingMetrics);
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.empty());
        doThrow(new IllegalStateException("metrics registry unavailable"))
                .when(failingMetrics).recordReadAccessAuditActorMissing(ReadAccessEndpointCategory.ALERT_DETAIL);

        assertThatThrownBy(() -> audited.auditOrThrow(simpleTarget(), ReadAccessAuditOutcome.SUCCESS, 1, "corr-metrics"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Sensitive read actor identity is unavailable.");
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
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

    @Test
    void shouldRejectMissingActorWhenCallerRequiresFailClosedRead() {
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.empty());
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(
                ReadAccessEndpointCategory.POLICY_TRUST_CANDIDATE,
                ReadAccessResourceType.POLICY_TRUST,
                "request-1",
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> service.auditOrThrow(target, ReadAccessAuditOutcome.SUCCESS, 1, "corr-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Sensitive read actor identity is unavailable.");

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(meterRegistry.get("fraud_read_access_audit_actor_missing_total")
                .tag("endpoint_category", "policy_trust_candidate")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("fraud_platform_read_access_audit_persistence_failures_total")
                .tag("endpoint_category", "policy_trust_candidate")
                .counter()).isNull();
    }

    @Test
    void strictReadRejectsUnverifiedUserIdWithoutInstitutionalIdentity() {
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "unverified-user", Set.of(AnalystRole.ANALYST), AnalystRole.ANALYST.authorities())));

        assertThatThrownBy(() -> service.auditOrThrow(simpleTarget(), ReadAccessAuditOutcome.SUCCESS, 1, "corr-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Sensitive read actor identity is unavailable.");

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void bestEffortReadKeepsUnverifiedIdentityInSeparateNonPiiNamespace() {
        when(currentAnalystUser.get()).thenReturn(java.util.Optional.of(new AnalystPrincipal(
                "unverified-user", Set.of(AnalystRole.ANALYST), AnalystRole.ANALYST.authorities())));

        service.audit(simpleTarget(), ReadAccessAuditOutcome.SUCCESS, 1, "corr-1");

        ArgumentCaptor<ReadAccessAuditEventDocument> captor = ArgumentCaptor.forClass(ReadAccessAuditEventDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().actorId()).startsWith("unverified:").doesNotContain("unverified-user");
    }

    private ReadAccessAuditTarget simpleTarget() {
        return new ReadAccessAuditTarget(ReadAccessEndpointCategory.ALERT_DETAIL,
                ReadAccessResourceType.ALERT, "alert-1", null, null, null, null);
    }
}
