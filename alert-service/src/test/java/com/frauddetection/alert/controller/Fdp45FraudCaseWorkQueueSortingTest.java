package com.frauddetection.alert.controller;

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
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueSortingTest {

    private final FraudCaseManagementService service = mock(FraudCaseManagementService.class);
    private final FraudCaseController controller = new FraudCaseController(
            service,
            new FraudCaseResponseMapper(new AlertResponseMapper()),
            mock(AlertServiceMetrics.class),
            mock(SensitiveReadAuditService.class)
    );

    @Test
    void shouldAllowOnlyStableSortFieldsAndDirections() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 0, 20, false, null));

        controller.workQueue(0, 20, "updatedAt,asc", null, null, null, null, null, null, null, null, null, null, null, params("sort", "updatedAt,asc"));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), captor.capture());
        Sort.Order order = captor.getValue().getSort().getOrderFor("updatedAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);

        assertThatThrownBy(() -> controller.workQueue(0, 20, "customerId,desc", null, null, null, null, null, null, null, null, null, null, null, params("sort", "customerId,desc")))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_SORT_FIELD");
        assertThatThrownBy(() -> controller.workQueue(0, 20, "createdAt,sideways", null, null, null, null, null, null, null, null, null, null, null, params("sort", "createdAt,sideways")))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_SORT_DIRECTION");
    }

    private LinkedMultiValueMap<String, String> params(String... values) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            params.add(values[index], values[index + 1]);
        }
        return params;
    }
}
