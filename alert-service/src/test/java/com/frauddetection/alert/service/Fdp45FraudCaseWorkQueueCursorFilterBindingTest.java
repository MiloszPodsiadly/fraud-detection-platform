package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueProperties;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueQueryException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fdp45FraudCaseWorkQueueCursorFilterBindingTest {

    private static final Sort.Order SORT = Sort.Order.desc("createdAt");
    private static final Instant CREATED_FROM = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant CREATED_TO = Instant.parse("2026-05-11T00:00:00Z");

    @Test
    void shouldAcceptCursorWhenFiltersAndSortMatch() {
        Fixture fixture = fixture(FraudCaseStatus.OPEN, "analyst-1", CREATED_FROM, CREATED_TO, "alert-1");
        String cursor = fixture.firstCursor();
        when(fixture.searchRepository.searchSliceAfter(any(), eq(2), eq(SORT), any()))
                .thenReturn(new SliceImpl<>(List.of(caseDocument("case-1", "2026-05-10T10:00:00Z")), fixture.pageable, false));

        fixture.service.workQueue(FraudCaseStatus.OPEN, "analyst-1", FraudCasePriority.HIGH, RiskLevel.HIGH,
                CREATED_FROM, CREATED_TO, null, null, "alert-1", fixture.pageable, cursor, SORT);

        verify(fixture.searchRepository).searchSliceAfter(any(), eq(2), eq(SORT), any());
    }

    @Test
    void shouldRejectCursorWhenStatusAssigneeDateLinkedAlertOrSortChangesBeforeRepositoryAccess() {
        assertMismatch(FraudCaseStatus.CLOSED, "analyst-1", CREATED_FROM, CREATED_TO, "alert-1", SORT);
        assertMismatch(FraudCaseStatus.OPEN, "analyst-2", CREATED_FROM, CREATED_TO, "alert-1", SORT);
        assertMismatch(FraudCaseStatus.OPEN, "analyst-1", Instant.parse("2026-05-02T00:00:00Z"), CREATED_TO, "alert-1", SORT);
        assertMismatch(FraudCaseStatus.OPEN, "analyst-1", CREATED_FROM, Instant.parse("2026-05-12T00:00:00Z"), "alert-1", SORT);
        assertMismatch(FraudCaseStatus.OPEN, "analyst-1", CREATED_FROM, CREATED_TO, "alert-2", SORT);
        assertMismatch(FraudCaseStatus.OPEN, "analyst-1", CREATED_FROM, CREATED_TO, "alert-1", Sort.Order.asc("createdAt"));
    }

    @Test
    void shouldRejectCursorWhenFilterIsAddedOrRemoved() {
        Fixture unfiltered = fixture(null, null, null, null, null);
        String unfilteredCursor = unfiltered.firstCursor();
        assertInvalid(unfiltered, FraudCaseStatus.OPEN, null, null, null, null, SORT, unfilteredCursor);

        Fixture filtered = fixture(FraudCaseStatus.OPEN, null, null, null, null);
        String filteredCursor = filtered.firstCursor();
        assertInvalid(filtered, null, null, null, null, null, SORT, filteredCursor);
    }

    private void assertMismatch(
            FraudCaseStatus status,
            String assignee,
            Instant createdFrom,
            Instant createdTo,
            String linkedAlertId,
            Sort.Order sort
    ) {
        Fixture fixture = fixture(FraudCaseStatus.OPEN, "analyst-1", CREATED_FROM, CREATED_TO, "alert-1");
        String cursor = fixture.firstCursor();
        assertInvalid(fixture, status, assignee, createdFrom, createdTo, linkedAlertId, sort, cursor);
    }

    private void assertInvalid(
            Fixture fixture,
            FraudCaseStatus status,
            String assignee,
            Instant createdFrom,
            Instant createdTo,
            String linkedAlertId,
            Sort.Order sort,
            String cursor
    ) {
        clearInvocations(fixture.searchRepository);

        assertThatThrownBy(() -> fixture.service.workQueue(status, assignee, FraudCasePriority.HIGH, RiskLevel.HIGH,
                createdFrom, createdTo, null, null, linkedAlertId, fixture.pageable, cursor, sort))
                .isInstanceOf(FraudCaseWorkQueueQueryException.class)
                .extracting("code")
                .isEqualTo("INVALID_CURSOR");

        verify(fixture.searchRepository, never()).searchSliceAfter(any(), anyInt(), any(), any());
    }

    private Fixture fixture(
            FraudCaseStatus status,
            String assignee,
            Instant createdFrom,
            Instant createdTo,
            String linkedAlertId
    ) {
        FraudCaseSearchRepository searchRepository = mock(FraudCaseSearchRepository.class);
        var pageable = PageRequest.of(0, 2, Sort.by(SORT));
        when(searchRepository.searchSlice(any(), eq(pageable)))
                .thenReturn(new SliceImpl<>(List.of(
                        caseDocument("case-3", "2026-05-10T12:00:00Z"),
                        caseDocument("case-2", "2026-05-10T11:00:00Z")
                ), pageable, true));
        FraudCaseQueryService service = new FraudCaseQueryService(
                mock(FraudCaseRepository.class),
                mock(FraudCaseAuditRepository.class),
                searchRepository,
                new FraudCaseResponseMapper(new AlertResponseMapper()),
                new FraudCaseWorkQueueProperties(Duration.ofHours(24), "test-work-queue-cursor-secret")
        );
        String cursor = service.workQueue(status, assignee, FraudCasePriority.HIGH, RiskLevel.HIGH,
                createdFrom, createdTo, null, null, linkedAlertId, pageable).nextCursor();
        return new Fixture(service, searchRepository, pageable, cursor);
    }

    private FraudCaseDocument caseDocument(String caseId, String createdAt) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(caseId);
        document.setCaseNumber("FC-" + caseId);
        document.setStatus(FraudCaseStatus.OPEN);
        document.setPriority(FraudCasePriority.HIGH);
        document.setRiskLevel(RiskLevel.HIGH);
        document.setCreatedAt(Instant.parse(createdAt));
        document.setUpdatedAt(Instant.parse(createdAt));
        return document;
    }

    private record Fixture(
            FraudCaseQueryService service,
            FraudCaseSearchRepository searchRepository,
            PageRequest pageable,
            String firstCursor
    ) {
    }
}
