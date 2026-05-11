package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueCursorPageCombinationTest {

    private final FraudCaseManagementService service = mock(FraudCaseManagementService.class);
    private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
    private final FraudCaseController controller = new FraudCaseController(
            service,
            new FraudCaseResponseMapper(new AlertResponseMapper()),
            metrics,
            mock(SensitiveReadAuditService.class)
    );

    @Test
    void shouldAllowCursorWithDefaultPageZero() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class), any(String.class), any(Sort.Order.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 0, 20, false, null));

        controller.workQueue(0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, params("cursor", "cursor-1"));

        verify(service).workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class), any(String.class), any(Sort.Order.class));
    }

    @Test
    void shouldRejectCursorWithNonZeroPageBeforeServiceCall() {
        assertRejected(9);
        assertRejected(1000);

        verify(service, never()).workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class), any(String.class), any(Sort.Order.class));
    }

    @Test
    void shouldKeepOffsetPageModeWhenCursorIsAbsent() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 9, 20, false, null));

        controller.workQueue(9, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, params("page", "9"));

        verify(service).workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    private void assertRejected(int page) {
        assertThatThrownBy(() -> controller.workQueue(page, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, params("cursor", "cursor-1", "page", String.valueOf(page))))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_CURSOR_PAGE_COMBINATION");
    }

    private LinkedMultiValueMap<String, String> params(String... values) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            params.add(values[index], values[index + 1]);
        }
        return params;
    }
}
