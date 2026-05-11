package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueProperties;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueCursorSizeChangeTest {

    @Test
    void shouldAllowChangingSizeWhenCursorFilterAndSortRemainTheSame() {
        FraudCaseSearchRepository searchRepository = mock(FraudCaseSearchRepository.class);
        Sort.Order sort = Sort.Order.desc("createdAt");
        var firstPageable = PageRequest.of(0, 2, Sort.by(sort));
        var secondPageable = PageRequest.of(0, 5, Sort.by(sort));
        FraudCaseDocument case3 = caseDocument("case-3", "2026-05-10T12:00:00Z");
        FraudCaseDocument case2 = caseDocument("case-2", "2026-05-10T11:00:00Z");
        FraudCaseDocument case1 = caseDocument("case-1", "2026-05-10T10:00:00Z");
        FraudCaseDocument case0 = caseDocument("case-0", "2026-05-10T09:00:00Z");
        when(searchRepository.searchSlice(any(), eq(firstPageable)))
                .thenReturn(new SliceImpl<>(List.of(case3, case2), firstPageable, true));
        when(searchRepository.searchSliceAfter(any(), eq(5), eq(sort), any()))
                .thenReturn(new SliceImpl<>(List.of(case1, case0), secondPageable, true));
        FraudCaseQueryService service = new FraudCaseQueryService(
                mock(FraudCaseRepository.class),
                mock(FraudCaseAuditRepository.class),
                searchRepository,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                new FraudCaseWorkQueueProperties(Duration.ofHours(24), "test-work-queue-cursor-secret")
        );

        var first = service.workQueue(null, null, null, null, null, null, null, null, null, firstPageable);
        var second = service.workQueue(null, null, null, null, null, null, null, null, null, secondPageable, first.nextCursor(), sort);

        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.content()).extracting("caseId").containsExactly("case-1", "case-0");
        assertThat(second.content()).extracting("caseId").doesNotContain("case-3", "case-2");
        assertThat(second.nextCursor()).isNotBlank();
        verify(searchRepository).searchSliceAfter(any(), eq(5), eq(sort), any());
    }

    private FraudCaseDocument caseDocument(String caseId, String createdAt) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(caseId);
        document.setCaseNumber("FC-" + caseId);
        document.setStatus(FraudCaseStatus.OPEN);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setCreatedAt(Instant.parse(createdAt));
        document.setUpdatedAt(Instant.parse(createdAt));
        return document;
    }
}
