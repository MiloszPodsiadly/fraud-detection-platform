package com.frauddetection.alert.controller;

import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueFailedReadAuditTest {

    @Test
    void shouldAuditValidationFailureAsRejectedAttemptWithoutRawFilterValues() {
        SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
        FraudCaseController controller = controller(mock(FraudCaseManagementService.class), auditService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fraud-cases/work-queue");

        assertThatThrownBy(() -> controller.workQueue(0, 20, "createdAt,desc", null, "analyst-1", "different",
                null, null, null, null, null, null, null, request, new LinkedMultiValueMap<>()))
                .isInstanceOf(com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException.class);

        verify(auditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq(null),
                eq(ReadAccessAuditOutcome.REJECTED),
                eq(request)
        );
    }

    @Test
    void shouldAuditRepositoryFailureAsFailedAttempt() {
        FraudCaseManagementService service = mock(FraudCaseManagementService.class);
        SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("mongo unavailable"));
        FraudCaseController controller = controller(service, auditService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fraud-cases/work-queue");

        assertThatThrownBy(() -> controller.workQueue(0, 20, "createdAt,desc", null, null, null,
                null, null, null, null, null, null, null, request, new LinkedMultiValueMap<>()))
                .isInstanceOf(IllegalStateException.class);

        verify(auditService).auditAttempt(
                eq(ReadAccessEndpointCategory.FRAUD_CASE_WORK_QUEUE),
                eq(ReadAccessResourceType.FRAUD_CASE),
                eq(null),
                eq(ReadAccessAuditOutcome.FAILED),
                eq(request)
        );
    }

    private FraudCaseController controller(FraudCaseManagementService service, SensitiveReadAuditService auditService) {
        return new FraudCaseController(
                service,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                mock(AlertServiceMetrics.class),
                auditService
        );
    }
}
