package com.frauddetection.alert.service;

import com.frauddetection.alert.api.FraudCaseSlaStatus;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueSlaAgeTest {

    @Test
    void shouldDeriveReadOnlyAgeAndSlaFieldsWithoutPersistedSlaState() {
        FraudCaseSearchRepository searchRepository = mock(FraudCaseSearchRepository.class);
        FraudCaseDocument open = caseDocument("case-open", FraudCaseStatus.OPEN, "2026-05-10T12:00:00Z", "2026-05-11T07:00:00Z");
        FraudCaseDocument breached = caseDocument("case-breached", FraudCaseStatus.IN_REVIEW, "2026-05-09T12:00:00Z", "2026-05-10T07:00:00Z");
        FraudCaseDocument closed = caseDocument("case-closed", FraudCaseStatus.CLOSED, "2026-05-10T12:00:00Z", "2026-05-10T13:00:00Z");
        FraudCaseDocument unknown = caseDocument("case-unknown", FraudCaseStatus.OPEN, null, "2026-05-11T07:00:00Z");
        when(searchRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(open, breached, closed, unknown)));
        FraudCaseQueryService service = new FraudCaseQueryService(
                mock(FraudCaseRepository.class),
                mock(FraudCaseAuditRepository.class),
                searchRepository,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofHours(24)
        );

        var items = service.workQueue(null, null, null, null, null, null, null, null, null, PageRequest.of(0, 20)).getContent();

        assertThat(items.get(0).caseAgeSeconds()).isEqualTo(79_200L);
        assertThat(items.get(0).lastUpdatedAgeSeconds()).isEqualTo(10_800L);
        assertThat(items.get(0).slaDeadlineAt()).isEqualTo(Instant.parse("2026-05-11T12:00:00Z"));
        assertThat(items.get(0).slaStatus()).isEqualTo(FraudCaseSlaStatus.NEAR_BREACH);
        assertThat(items.get(1).slaStatus()).isEqualTo(FraudCaseSlaStatus.BREACHED);
        assertThat(items.get(2).slaStatus()).isEqualTo(FraudCaseSlaStatus.NOT_APPLICABLE);
        assertThat(items.get(3).slaStatus()).isEqualTo(FraudCaseSlaStatus.UNKNOWN);
    }

    private FraudCaseDocument caseDocument(String caseId, FraudCaseStatus status, String createdAt, String updatedAt) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(caseId);
        document.setCaseNumber("FC-" + caseId);
        document.setStatus(status);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.CRITICAL);
        document.setCreatedAt(createdAt == null ? null : Instant.parse(createdAt));
        document.setUpdatedAt(updatedAt == null ? null : Instant.parse(updatedAt));
        document.setLinkedAlertIds(List.of("alert-1", "alert-2"));
        return document;
    }
}
