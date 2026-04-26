package com.frauddetection.alert.audit;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldPublishAuditEventWithAuthenticatedAnalystActor() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditService service = new AuditService(new CurrentAnalystUser(), publisher);
        AnalystPrincipal principal = new AnalystPrincipal(
                "principal-1",
                Set.of(AnalystRole.ANALYST),
                AnalystRole.ANALYST.authorities()
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(AnalystAuthority.ALERT_DECISION_SUBMIT))
        ));

        service.audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "request-analyst"
        );

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publish(eventCaptor.capture());
        AuditEvent event = eventCaptor.getValue();
        assertThat(event.actor().userId()).isEqualTo("principal-1");
        assertThat(event.actor().roles()).containsExactly("ANALYST");
        assertThat(event.actor().authorities()).contains(AnalystAuthority.ALERT_DECISION_SUBMIT);
        assertThat(event.action()).isEqualTo(AuditAction.SUBMIT_ANALYST_DECISION);
        assertThat(event.resourceType()).isEqualTo(AuditResourceType.ALERT);
        assertThat(event.resourceId()).isEqualTo("alert-1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.failureCategory()).isEqualTo(AuditFailureCategory.NONE);
        assertThat(event.failureReason()).isNull();
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void shouldUseFallbackActorWhenSecurityContextIsMissing() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditService service = new AuditService(new CurrentAnalystUser(), publisher);

        service.audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                null,
                "request-analyst"
        );

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publish(eventCaptor.capture());
        AuditEvent event = eventCaptor.getValue();
        assertThat(event.actor().userId()).isEqualTo("request-analyst");
        assertThat(event.actor().roles()).isEmpty();
        assertThat(event.actor().authorities()).isEmpty();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    }

    @Test
    void shouldNormalizeMissingActorAndBlankOptionalFields() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditService service = new AuditService(new CurrentAnalystUser(), publisher);

        service.audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                " ",
                " ",
                AuditOutcome.FAILED,
                " "
        );

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publish(eventCaptor.capture());
        AuditEvent event = eventCaptor.getValue();
        assertThat(event.actor().userId()).isEqualTo("unknown");
        assertThat(event.correlationId()).isNull();
        assertThat(event.failureReason()).isNull();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(event.failureCategory()).isEqualTo(AuditFailureCategory.UNKNOWN);
    }

    @Test
    void shouldPublishAuditEventWithExplicitOutcomeAndFailureReason() {
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        AuditService service = new AuditService(new CurrentAnalystUser(), publisher);

        service.audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-1",
                "request-analyst",
                AuditOutcome.REJECTED,
                "INSUFFICIENT_AUTHORITY"
        );

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publish(eventCaptor.capture());
        AuditEvent event = eventCaptor.getValue();
        assertThat(event.action()).isEqualTo(AuditAction.UPDATE_FRAUD_CASE);
        assertThat(event.resourceType()).isEqualTo(AuditResourceType.FRAUD_CASE);
        assertThat(event.resourceId()).isEqualTo("case-1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.actor().userId()).isEqualTo("request-analyst");
        assertThat(event.outcome()).isEqualTo(AuditOutcome.REJECTED);
        assertThat(event.failureCategory()).isEqualTo(AuditFailureCategory.AUTHORIZATION);
        assertThat(event.failureReason()).isEqualTo("INSUFFICIENT_AUTHORITY");
    }

    @Test
    void shouldPublishAuditEventToAllConfiguredPublishersInOrder() {
        AuditEventPublisher durablePublisher = mock(AuditEventPublisher.class);
        AuditEventPublisher structuredPublisher = mock(AuditEventPublisher.class);
        AuditService service = new AuditService(new CurrentAnalystUser(), List.of(durablePublisher, structuredPublisher));

        service.audit(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "corr-1",
                "request-analyst"
        );

        var inOrder = inOrder(durablePublisher, structuredPublisher);
        inOrder.verify(durablePublisher).publish(org.mockito.ArgumentMatchers.any(AuditEvent.class));
        inOrder.verify(structuredPublisher).publish(org.mockito.ArgumentMatchers.any(AuditEvent.class));
    }
}
