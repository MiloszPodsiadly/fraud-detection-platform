package com.frauddetection.alert.controller;

import com.frauddetection.alert.exception.AlertServiceExceptionHandler;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueuePaginationTest {

    private final FraudCaseManagementService service = mock(FraudCaseManagementService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final FraudCaseController controller = new FraudCaseController(
            service,
            new FraudCaseResponseMapper(new AlertResponseMapper()),
            metrics,
            mock(SensitiveReadAuditService.class)
    );

    @Test
    void shouldEnforceBoundedPaginationAndPassPageableToAuthoritativeQueryPath() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 2, 100, false, null));

        controller.workQueue(2, 100, "createdAt,desc", null, null, null, null, null, null, null, null, null, null, null, params("page", "2", "size", "100"));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);

        assertThatThrownBy(() -> controller.workQueue(-1, 20, "createdAt,desc", null, null, null, null, null, null, null, null, null, null, null, params()))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAGE_REQUEST");
        assertThatThrownBy(() -> controller.workQueue(0, 101, "createdAt,desc", null, null, null, null, null, null, null, null, null, null, null, params()))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAGE_REQUEST");
    }

    @SuppressWarnings("unused")
    private AlertServiceExceptionHandler exceptionHandler() {
        return new AlertServiceExceptionHandler();
    }

    private LinkedMultiValueMap<String, String> params(String... values) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            params.add(values[index], values[index + 1]);
        }
        return params;
    }
}
