package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernanceAuditServiceTest {

    private final GovernanceAuditRepository repository = mock(GovernanceAuditRepository.class);
    private final GovernanceAdvisoryClient advisoryClient = mock(GovernanceAdvisoryClient.class);
    private final GovernanceAuditService service = new GovernanceAuditService(
            repository,
            advisoryClient,
            new CurrentAnalystUser(),
            new GovernanceAuditProperties(URI.create("http://localhost:8090"), 50, 500, Duration.ofSeconds(2)),
            new GovernanceAuditRequestValidator()
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAppendAuditWithBackendDerivedActorAndAdvisorySnapshot() {
        setAnalystPrincipal();
        when(advisoryClient.getAdvisory("advisory-1")).thenReturn(sampleAdvisory());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countByAdvisoryEventId("advisory-1")).thenReturn(1L);

        GovernanceAuditEventResponse response = service.appendAudit(
                "advisory-1",
                new GovernanceAuditRequest("ACKNOWLEDGED", " Reviewed by operator ")
        );

        ArgumentCaptor<GovernanceAuditEventDocument> documentCaptor = ArgumentCaptor.forClass(GovernanceAuditEventDocument.class);
        verify(repository).save(documentCaptor.capture());
        GovernanceAuditEventDocument saved = documentCaptor.getValue();
        assertThat(saved.getActorId()).isEqualTo("principal-1");
        assertThat(saved.getActorRoles()).containsExactly("ANALYST");
        assertThat(saved.getModelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(saved.getAdvisoryConfidenceContext()).isEqualTo("SUFFICIENT_DATA");
        assertThat(java.util.Arrays.stream(saved.getClass().getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("lifecycleStatus");
        assertThat(response.actorId()).isEqualTo("principal-1");
        assertThat(response.note()).isEqualTo("Reviewed by operator");
    }

    @Test
    void shouldFailAuditWriteClearlyWhenPersistenceUnavailable() {
        setAnalystPrincipal();
        when(advisoryClient.getAdvisory("advisory-1")).thenReturn(sampleAdvisory());
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("mongo down"));

        assertThatThrownBy(() -> service.appendAudit("advisory-1", new GovernanceAuditRequest("ACKNOWLEDGED", null)))
                .isInstanceOf(GovernanceAuditPersistenceUnavailableException.class);
    }

    @Test
    void shouldReturnUnavailableHistoryWhenPersistenceUnavailable() {
        when(repository.findByAdvisoryEventIdOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));

        GovernanceAuditHistoryResponse response = service.history("advisory-1");

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.auditEvents()).isEmpty();
    }

    @Test
    void shouldRejectInvalidDecisionBeforePersistence() {
        setAnalystPrincipal();

        assertThatThrownBy(() -> service.appendAudit("advisory-1", new GovernanceAuditRequest("APPROVE_MODEL", null)))
                .isInstanceOf(InvalidGovernanceAuditDecisionException.class);
    }

    @Test
    void shouldNormalizeBlankNoteToNull() {
        setAnalystPrincipal();
        when(advisoryClient.getAdvisory("advisory-1")).thenReturn(sampleAdvisory());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countByAdvisoryEventId("advisory-1")).thenReturn(1L);

        service.appendAudit("advisory-1", new GovernanceAuditRequest("NEEDS_FOLLOW_UP", "   \t  "));

        ArgumentCaptor<GovernanceAuditEventDocument> documentCaptor = ArgumentCaptor.forClass(GovernanceAuditEventDocument.class);
        verify(repository).save(documentCaptor.capture());
        assertThat(documentCaptor.getValue().getNote()).isNull();
    }

    @Test
    void shouldRejectOversizedNoteBeforeAdvisoryLookupAndPersistence() {
        setAnalystPrincipal();

        assertThatThrownBy(() -> service.appendAudit(
                "advisory-1",
                new GovernanceAuditRequest("ACKNOWLEDGED", "x".repeat(GovernanceAuditRequestValidator.MAX_NOTE_LENGTH + 1))
        )).isInstanceOf(InvalidGovernanceAuditRequestException.class);

        verify(advisoryClient, org.mockito.Mockito.never()).getAdvisory(any());
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    private void setAnalystPrincipal() {
        AnalystPrincipal principal = new AnalystPrincipal(
                "principal-1",
                Set.of(AnalystRole.ANALYST),
                AnalystRole.ANALYST.authorities()
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(AnalystAuthority.GOVERNANCE_ADVISORY_AUDIT_WRITE))
        ));
    }

    private GovernanceAdvisorySnapshot sampleAdvisory() {
        return new GovernanceAdvisorySnapshot(
                "advisory-1",
                "python-logistic-fraud-model",
                "2026-04-21.trained.v1",
                "HIGH",
                "HIGH",
                "SUFFICIENT_DATA"
        );
    }
}
