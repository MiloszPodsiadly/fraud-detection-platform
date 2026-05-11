package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseSummaryResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueContractCompatibilityTest {

    @Test
    void shouldKeepOldListContractSeparateFromDedicatedWorkQueueContract() {
        FraudCaseManagementService service = mock(FraudCaseManagementService.class);
        FraudCaseController controller = new FraudCaseController(
                service,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                mock(AlertServiceMetrics.class),
                mock(SensitiveReadAuditService.class)
        );
        when(service.listCases(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(caseDocument())));
        when(service.workQueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new FraudCaseWorkQueueSliceResponse(List.of(), 0, 20, false, null));

        var oldList = controller.listCases(0, 20, null, null, null, null, null, null, null);
        var workQueue = controller.workQueue(0, 20, "createdAt,desc", null, null, null, null, null,
                null, null, null, null, null, null, new LinkedMultiValueMap<>());

        assertThat(oldList.content()).allSatisfy(item -> assertThat(item).isInstanceOf(FraudCaseSummaryResponse.class));
        assertThat(oldList.totalElements()).isEqualTo(1);
        assertThat(workQueue).isInstanceOf(FraudCaseWorkQueueSliceResponse.class);
        assertThat(workQueue.hasNext()).isFalse();
    }

    private FraudCaseDocument caseDocument() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setCaseNumber("FC-1");
        document.setStatus(FraudCaseStatus.OPEN);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setLinkedAlertIds(List.of("alert-1"));
        document.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        return document;
    }
}
