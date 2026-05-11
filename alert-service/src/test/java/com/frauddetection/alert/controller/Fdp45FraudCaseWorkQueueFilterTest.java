package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueFilterTest {

    private final FraudCaseManagementService service = mock(FraudCaseManagementService.class);
    private final FraudCaseController controller = new FraudCaseController(
            service,
            new FraudCaseResponseMapper(new AlertResponseMapper()),
            mock(AlertServiceMetrics.class),
            mock(SensitiveReadAuditService.class)
    );

    @Test
    void shouldPassOnlyAllowlistedFiltersAndRejectUnknownOrInvalidRanges() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 0, 20, false, null));
        Instant createdFrom = Instant.parse("2026-05-10T10:00:00Z");
        Instant createdTo = Instant.parse("2026-05-11T10:00:00Z");
        Instant updatedFrom = Instant.parse("2026-05-10T11:00:00Z");
        Instant updatedTo = Instant.parse("2026-05-11T11:00:00Z");

        controller.workQueue(0, 20, "createdAt,desc", FraudCaseStatus.OPEN, null, "investigator-1", FraudCasePriority.HIGH,
                RiskLevel.CRITICAL, createdFrom, createdTo, updatedFrom, updatedTo, "alert-1", null, params(
                        "status", "OPEN",
                        "assignedInvestigatorId", "investigator-1",
                        "priority", "HIGH",
                        "riskLevel", "CRITICAL",
                        "createdFrom", createdFrom.toString(),
                        "createdTo", createdTo.toString(),
                        "updatedFrom", updatedFrom.toString(),
                        "updatedTo", updatedTo.toString(),
                        "linkedAlertId", "alert-1"
                ));

        verify(service).workQueue(eq(FraudCaseStatus.OPEN), eq("investigator-1"), eq(FraudCasePriority.HIGH), eq(RiskLevel.CRITICAL),
                eq(createdFrom), eq(createdTo), eq(updatedFrom), eq(updatedTo), eq("alert-1"), any(Pageable.class));
        assertThatThrownBy(() -> controller.workQueue(0, 20, "createdAt,desc", null, null, null, null, null,
                createdTo, createdFrom, null, null, null, null, params()))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_FILTER_RANGE");
        assertThatThrownBy(() -> controller.workQueue(0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, params("customerId", "customer-1")))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_FILTER");
    }

    @Test
    void shouldNormalizeAssigneeAliasesBeforeConflictCheckAndRepositoryQuery() {
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 0, 20, false, null));

        controller.workQueue(0, 20, "createdAt,desc", null, " investigator-1 ", "investigator-1", null, null,
                null, null, null, null, null, null, params("assignee", " investigator-1 ", "assignedInvestigatorId", "investigator-1"));
        controller.workQueue(0, 20, "createdAt,desc", null, "   ", " investigator-2 ", null, null,
                null, null, null, null, null, null, params("assignee", "   ", "assignedInvestigatorId", " investigator-2 "));

        verify(service).workQueue(any(), eq("investigator-1"), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
        verify(service).workQueue(any(), eq("investigator-2"), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    private LinkedMultiValueMap<String, String> params(String... values) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            params.add(values[index], values[index + 1]);
        }
        return params;
    }
}
