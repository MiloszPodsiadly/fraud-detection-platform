package com.frauddetection.alert.audit.read;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SensitiveReadAuditServiceTest {

    @Test
    void shouldFailClosedWhenPolicyRequiresAuditPersistence() {
        ReadAccessAuditService delegate = mock(ReadAccessAuditService.class);
        SensitiveReadAuditPolicy policy = mock(SensitiveReadAuditPolicy.class);
        when(policy.failClosed()).thenReturn(true);
        doThrow(new IllegalStateException("mongo down")).when(delegate).auditOrThrow(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ReadAccessAuditOutcome.SUCCESS),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("corr-1")
        );
        SensitiveReadAuditService service = new SensitiveReadAuditService(delegate, policy);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn("corr-1");

        assertThatThrownBy(() -> service.audit(
                ReadAccessEndpointCategory.SYSTEM_TRUST_LEVEL,
                ReadAccessResourceType.SYSTEM_TRUST_LEVEL,
                null,
                1,
                request
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503 SERVICE_UNAVAILABLE");
    }

    @Test
    void shouldUseBestEffortWhenPolicyDoesNotRequireFailClosed() {
        ReadAccessAuditService delegate = mock(ReadAccessAuditService.class);
        SensitiveReadAuditPolicy policy = mock(SensitiveReadAuditPolicy.class);
        when(policy.failClosed()).thenReturn(false);
        SensitiveReadAuditService service = new SensitiveReadAuditService(delegate, policy);

        service.audit(
                ReadAccessEndpointCategory.OUTBOX_RECOVERY_BACKLOG,
                ReadAccessResourceType.OUTBOX_RECOVERY,
                null,
                250,
                null
        );

        verify(delegate).audit(
                org.mockito.ArgumentMatchers.argThat(target ->
                        target.endpointCategory() == ReadAccessEndpointCategory.OUTBOX_RECOVERY_BACKLOG
                                && target.resourceType() == ReadAccessResourceType.OUTBOX_RECOVERY
                                && target.resourceId() == null
                                && target.queryHash() == null),
                org.mockito.ArgumentMatchers.eq(ReadAccessAuditOutcome.SUCCESS),
                org.mockito.ArgumentMatchers.eq(100),
                org.mockito.ArgumentMatchers.isNull()
        );
    }
}
