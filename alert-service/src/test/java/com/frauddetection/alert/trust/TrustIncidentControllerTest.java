package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessAuditTarget;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class TrustIncidentControllerTest {

    private final TrustIncidentService service = mock(TrustIncidentService.class);
    private final TrustSignalCollector collector = mock(TrustSignalCollector.class);
    private final ReadAccessAuditService auditService = mock(ReadAccessAuditService.class);
    private final Environment environment = mock(Environment.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void shouldRateLimitSignalPreview() {
        TrustIncidentController controller = controller(1, false, "local");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(collector.collect()).thenReturn(List.of());

        controller.preview(auth(), request);

        assertThatThrownBy(() -> controller.preview(auth(), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verify(collector, times(1)).collect();
        verify(auditService, times(1)).audit(any(), eq(ReadAccessAuditOutcome.SUCCESS), eq(0), any());
        verifyNoInteractions(service);
    }

    @Test
    void shouldAuditPreviewReadWithoutWritingIncidents() {
        TrustIncidentController controller = controller(30, false, "local");
        when(request.getHeader("X-Correlation-Id")).thenReturn("corr-1");
        when(collector.collect()).thenReturn(List.of(signal("outbox:event-1")));

        TrustSignalPreviewResponse response = controller.preview(auth(), request);

        assertThat(response.preview()).isTrue();
        assertThat(response.signalCount()).isEqualTo(1);
        ArgumentCaptor<ReadAccessAuditTarget> targetCaptor = ArgumentCaptor.forClass(ReadAccessAuditTarget.class);
        verify(auditService).audit(targetCaptor.capture(), eq(ReadAccessAuditOutcome.SUCCESS), eq(1), eq("corr-1"));
        ReadAccessAuditTarget target = targetCaptor.getValue();
        assertThat(target.endpointCategory()).isEqualTo(ReadAccessEndpointCategory.PREVIEW_TRUST_INCIDENT_SIGNALS);
        assertThat(target.resourceType()).isEqualTo(ReadAccessResourceType.TRUST_INCIDENT_SIGNAL);
        assertThat(target.resourceId()).isNull();
        assertThat(target.queryHash()).isNull();
        assertThat(target.size()).isEqualTo(1);
        verify(service, never()).listOpen();
        verify(service, never()).refresh(any(), any(), any());
        verifyNoInteractions(service);
    }

    @Test
    void shouldFailClosedWhenPreviewAuditFailsInBankMode() {
        TrustIncidentController controller = controller(30, true, "bank");
        when(request.getHeader("X-Correlation-Id")).thenReturn("corr-1");
        when(collector.collect()).thenReturn(List.of(signal("coverage:gap")));
        doThrow(new DataAccessResourceFailureException("mongo unavailable"))
                .when(auditService).auditOrThrow(any(), eq(ReadAccessAuditOutcome.SUCCESS), eq(1), eq("corr-1"));

        assertThatThrownBy(() -> controller.preview(auth(), request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException responseStatusException = (ResponseStatusException) exception;
                    assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(responseStatusException.getReason()).isEqualTo("Trust incident signal preview audit unavailable.");
                });

        verify(auditService).auditOrThrow(any(), eq(ReadAccessAuditOutcome.SUCCESS), eq(1), eq("corr-1"));
        verifyNoInteractions(service);
    }

    private TrustIncidentController controller(int maxRequestsPerMinute, boolean bankModeFailClosed, String... profiles) {
        when(environment.getActiveProfiles()).thenReturn(profiles);
        TrustIncidentPreviewRateLimiter rateLimiter = new TrustIncidentPreviewRateLimiter(
                Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC),
                maxRequestsPerMinute
        );
        return new TrustIncidentController(service, collector, rateLimiter, auditService, environment, bankModeFailClosed);
    }

    private TestingAuthenticationToken auth() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("ops-admin", "n/a");
        authentication.setAuthenticated(true);
        return authentication;
    }

    private TrustSignal signal(String evidenceRef) {
        return new TrustSignal(
                "OUTBOX_TERMINAL_FAILURE",
                TrustIncidentSeverity.CRITICAL,
                "transactional_outbox",
                "fingerprint",
                List.of(evidenceRef)
        );
    }
}
