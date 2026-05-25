package com.frauddetection.alert.service;

import com.frauddetection.alert.api.FraudCaseSlaStatus;
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.fraudcase.FraudCaseSearchCriteria;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueCursor;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueCursorCodec;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueCursorQueryFingerprint;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueProperties;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class FraudCaseQueryService {

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudCaseSearchRepository searchRepository;
    private final Clock clock;
    private final Duration workQueueSla;
    private final FraudCaseWorkQueueCursorCodec cursorCodec;

    @Autowired
    public FraudCaseQueryService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseSearchRepository searchRepository,
            FraudCaseWorkQueueProperties workQueueProperties
    ) {
        this(
                fraudCaseRepository,
                searchRepository,
                Clock.systemUTC(),
                workQueueProperties.sla(),
                new FraudCaseWorkQueueCursorCodec(workQueueProperties.cursorSigningSecret())
        );
    }

    FraudCaseQueryService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseSearchRepository searchRepository,
            Clock clock,
            Duration workQueueSla
    ) {
        this(fraudCaseRepository, searchRepository, clock, workQueueSla, FraudCaseWorkQueueCursorCodec.localDefault());
    }

    FraudCaseQueryService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseSearchRepository searchRepository,
            Clock clock,
            Duration workQueueSla,
            FraudCaseWorkQueueCursorCodec cursorCodec
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.searchRepository = searchRepository;
        this.clock = clock;
        if (workQueueSla == null || workQueueSla.isNegative() || workQueueSla.isZero()) {
            throw new IllegalArgumentException("Fraud case work queue SLA must be a positive duration.");
        }
        this.workQueueSla = workQueueSla;
        this.cursorCodec = cursorCodec;
    }

    public FraudCaseDocument getCase(String caseId) {
        return fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new FraudCaseNotFoundException(caseId));
    }

    public FraudCaseWorkQueueSliceResponse workQueue(
            FraudCaseStatus status,
            String assignee,
            FraudCasePriority priority,
            RiskLevel riskLevel,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            String linkedAlertId,
            Pageable pageable
    ) {
        Sort.Order sortOrder = primarySortOrder(pageable);
        return workQueue(status, assignee, priority, riskLevel, createdFrom, createdTo, updatedFrom, updatedTo, linkedAlertId, pageable, null, sortOrder);
    }

    public FraudCaseWorkQueueSliceResponse workQueue(
            FraudCaseStatus status,
            String assignee,
            FraudCasePriority priority,
            RiskLevel riskLevel,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            String linkedAlertId,
            Pageable pageable,
            String encodedCursor,
            Sort.Order sortOrder
    ) {
        Instant now = clock.instant();
        FraudCaseSearchCriteria criteria = new FraudCaseSearchCriteria(
                status,
                assignee,
                priority,
                riskLevel,
                createdFrom,
                createdTo,
                updatedFrom,
                updatedTo,
                linkedAlertId
        );
        Sort.Order effectiveSort = sortOrder == null ? primarySortOrder(pageable) : sortOrder;
        String queryHash = FraudCaseWorkQueueCursorQueryFingerprint.hash(
                status,
                assignee,
                priority,
                riskLevel,
                createdFrom,
                createdTo,
                updatedFrom,
                updatedTo,
                linkedAlertId,
                effectiveSort,
                FraudCaseWorkQueueCursorCodec.VERSION
        );
        FraudCaseWorkQueueCursor cursor = cursorCodec.decode(encodedCursor, effectiveSort, queryHash);
        var documentSlice = cursor == null
                ? searchRepository.searchSlice(criteria, pageable)
                : searchRepository.searchSliceAfter(criteria, pageable.getPageSize(), effectiveSort, cursor);
        var content = documentSlice.getContent().stream()
                .map(document -> toWorkQueueItem(document, now))
                .toList();
        String nextCursor = documentSlice.hasNext() && !documentSlice.getContent().isEmpty()
                ? cursorCodec.encode(effectiveSort, documentSlice.getContent().getLast(), queryHash)
                : null;
        return new FraudCaseWorkQueueSliceResponse(
                content,
                cursor == null ? documentSlice.getNumber() : 0,
                documentSlice.getSize(),
                documentSlice.hasNext(),
                cursor == null && documentSlice.hasNext() ? documentSlice.getNumber() + 1 : null,
                nextCursor,
                formatSort(effectiveSort)
        );
    }

    public FraudCaseWorkQueueSummaryResponse globalFraudCaseSummary() {
        return new FraudCaseWorkQueueSummaryResponse(fraudCaseRepository.count(), clock.instant());
    }

    private Sort.Order primarySortOrder(Pageable pageable) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return Sort.Order.desc("createdAt");
        }
        return pageable.getSort().stream()
                .findFirst()
                .orElse(Sort.Order.desc("createdAt"));
    }

    private String formatSort(Sort.Order sortOrder) {
        return sortOrder.getProperty() + "," + sortOrder.getDirection().name().toLowerCase(java.util.Locale.ROOT);
    }

    private FraudCaseWorkQueueItemResponse toWorkQueueItem(FraudCaseDocument document, Instant now) {
        Instant createdAt = document.getCreatedAt();
        Instant updatedAt = document.getUpdatedAt();
        Instant deadline = createdAt == null ? null : createdAt.plus(workQueueSla);
        return new FraudCaseWorkQueueItemResponse(
                document.getCaseId(),
                document.getCaseNumber(),
                document.getStatus(),
                document.getPriority(),
                document.getRiskLevel(),
                document.getAssignedInvestigatorId(),
                createdAt,
                updatedAt,
                ageSeconds(createdAt, now),
                ageSeconds(updatedAt, now),
                slaStatus(document.getStatus(), createdAt, now, deadline),
                deadline,
                document.getLinkedAlertIds() == null ? 0 : document.getLinkedAlertIds().size()
        );
    }

    private Long ageSeconds(Instant timestamp, Instant now) {
        if (timestamp == null || now == null || timestamp.isAfter(now)) {
            return null;
        }
        return Duration.between(timestamp, now).getSeconds();
    }

    private FraudCaseSlaStatus slaStatus(FraudCaseStatus status, Instant createdAt, Instant now, Instant deadline) {
        if (createdAt == null || now == null || deadline == null) {
            return FraudCaseSlaStatus.UNKNOWN;
        }
        if (status == FraudCaseStatus.CLOSED
                || status == FraudCaseStatus.RESOLVED
                || status == FraudCaseStatus.CONFIRMED_FRAUD
                || status == FraudCaseStatus.FALSE_POSITIVE) {
            return FraudCaseSlaStatus.NOT_APPLICABLE;
        }
        if (!now.isBefore(deadline)) {
            return FraudCaseSlaStatus.BREACHED;
        }
        long totalSeconds = Math.max(1L, workQueueSla.getSeconds());
        long remainingSeconds = Duration.between(now, deadline).getSeconds();
        return remainingSeconds <= Math.max(1L, totalSeconds / 5)
                ? FraudCaseSlaStatus.NEAR_BREACH
                : FraudCaseSlaStatus.WITHIN_SLA;
    }
}
