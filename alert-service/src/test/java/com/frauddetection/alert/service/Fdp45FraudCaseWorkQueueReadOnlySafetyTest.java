package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueReadOnlySafetyTest {

    @Test
    void shouldNotMutateCaseAuditOrPersistDerivedSlaFieldsWhenReadingWorkQueue() {
        FraudCaseRepository repository = mock(FraudCaseRepository.class);
        FraudCaseAuditRepository auditRepository = mock(FraudCaseAuditRepository.class);
        FraudCaseSearchRepository searchRepository = mock(FraudCaseSearchRepository.class);
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("case-1");
        document.setStatus(FraudCaseStatus.OPEN);
        document.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-10T11:00:00Z"));
        when(searchRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(document)));
        FraudCaseQueryService service = new FraudCaseQueryService(
                repository,
                auditRepository,
                searchRepository,
                new FraudCaseResponseMapper(new AlertResponseMapper())
        );

        var item = service.workQueue(null, null, null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getContent()
                .getFirst();

        assertThat(item.caseAgeSeconds()).isNotNull();
        assertThat(item.slaStatus()).isNotNull();
        verify(repository, never()).save(any(FraudCaseDocument.class));
        verify(auditRepository, never()).save(any());
        verify(repository, never()).delete(any());
    }
}
