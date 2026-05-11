package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueDuplicateParamTest {

    private final FraudCaseManagementService service = mock(FraudCaseManagementService.class);
    private final FraudCaseController controller = new FraudCaseController(
            service,
            new FraudCaseResponseMapper(new AlertResponseMapper()),
            mock(AlertServiceMetrics.class),
            mock(SensitiveReadAuditService.class)
    );

    @Test
    void shouldRejectDuplicateSingleValuedQueryParamsBeforeQueryExecution() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 0, 20, false, null));
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("status", "OPEN");
        params.add("status", "IN_REVIEW");

        assertThatThrownBy(() -> controller.workQueue(
                0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, params
        ))
                .isInstanceOf(com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("DUPLICATE_QUERY_PARAM");
        verify(service, never()).workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    @SuppressWarnings("unused")
    private AlertServiceExceptionHandler exceptionHandler() {
        return new AlertServiceExceptionHandler();
    }
}
