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

class Fdp45FraudCaseWorkQueueCursorPaginationTest {

    @Test
    void shouldReturnNextCursorAndUseCursorRepositoryPathForNextSlice() {
        FraudCaseSearchRepository searchRepository = mock(FraudCaseSearchRepository.class);
        Sort.Order sort = Sort.Order.desc("createdAt");
        var pageable = PageRequest.of(0, 2, Sort.by(sort));
        FraudCaseDocument first = caseDocument("case-3", "2026-05-10T12:00:00Z");
        FraudCaseDocument second = caseDocument("case-2", "2026-05-10T11:00:00Z");
        FraudCaseDocument third = caseDocument("case-1", "2026-05-10T10:00:00Z");
        when(searchRepository.searchSlice(any(), eq(pageable))).thenReturn(new SliceImpl<>(List.of(first, second), pageable, true));
        when(searchRepository.searchSliceAfter(any(), eq(2), eq(sort), any()))
                .thenReturn(new SliceImpl<>(List.of(third), pageable, false));
        FraudCaseQueryService service = new FraudCaseQueryService(
                mock(FraudCaseRepository.class),
                mock(FraudCaseAuditRepository.class),
                searchRepository,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                new FraudCaseWorkQueueProperties(Duration.ofHours(24), "test-work-queue-cursor-secret")
        );

        var firstResponse = service.workQueue(null, null, null, null, null, null, null, null, null, pageable);
        var secondResponse = service.workQueue(null, null, null, null, null, null, null, null, null, pageable, firstResponse.nextCursor(), sort);

        assertThat(firstResponse.nextCursor()).isNotBlank();
        assertThat(firstResponse.nextPage()).isEqualTo(1);
        assertThat(firstResponse.sort()).isEqualTo("createdAt,desc");
        assertThat(secondResponse.content()).extracting("caseId").containsExactly("case-1");
        assertThat(secondResponse.nextPage()).isNull();
        assertThat(secondResponse.nextCursor()).isNull();
        verify(searchRepository).searchSliceAfter(any(), eq(2), eq(sort), any());
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
