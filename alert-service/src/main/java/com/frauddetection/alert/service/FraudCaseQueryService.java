package com.frauddetection.alert.service;

import com.frauddetection.alert.api.FraudCaseAuditResponse;
import com.frauddetection.alert.api.FraudCaseSlaStatus;
import com.frauddetection.alert.api.FraudCaseSummaryResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueItemResponse;
import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.fraudcase.FraudCaseSearchCriteria;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueCursor;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueCursorCodec;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueProperties;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.persistence.FraudCaseAuditEntryDocument;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class FraudCaseQueryService {

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudCaseAuditRepository auditRepository;
    private final FraudCaseSearchRepository searchRepository;
    private final FraudCaseResponseMapper responseMapper;
    private final Clock clock;
    private final Duration workQueueSla;
    private final FraudCaseWorkQueueCursorCodec cursorCodec;

    @Autowired
    public FraudCaseQueryService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseAuditRepository auditRepository,
            FraudCaseSearchRepository searchRepository,
            FraudCaseResponseMapper responseMapper,
            FraudCaseWorkQueueProperties workQueueProperties
    ) {
        this(
                fraudCaseRepository,
                auditRepository,
                searchRepository,
                responseMapper,
                Clock.systemUTC(),
                workQueueProperties.sla(),
                new FraudCaseWorkQueueCursorCodec(workQueueProperties.cursorSigningSecret())
        );
    }

    FraudCaseQueryService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseAuditRepository auditRepository,
            FraudCaseSearchRepository searchRepository,
            FraudCaseResponseMapper responseMapper,
            Clock clock,
            Duration workQueueSla
    ) {
        this(fraudCaseRepository, auditRepository, searchRepository, responseMapper, clock, workQueueSla, FraudCaseWorkQueueCursorCodec.localDefault());
    }

    FraudCaseQueryService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseAuditRepository auditRepository,
            FraudCaseSearchRepository searchRepository,
            FraudCaseResponseMapper responseMapper,
            Clock clock,
            Duration workQueueSla,
            FraudCaseWorkQueueCursorCodec cursorCodec
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.auditRepository = auditRepository;
        this.searchRepository = searchRepository;
        this.responseMapper = responseMapper;
        this.clock = clock;
        if (workQueueSla == null || workQueueSla.isNegative() || workQueueSla.isZero()) {
            throw new IllegalArgumentException("Fraud case work queue SLA must be a positive duration.");
        }
        this.workQueueSla = workQueueSla;
        this.cursorCodec = cursorCodec;
    }

    @Deprecated(forRemoval = false)
    public List<FraudCaseDocument> listCases() {
        return fraudCaseRepository.findAll();
    }

    public Page<FraudCaseDocument> listCases(Pageable pageable) {
        return searchRepository.search(emptyCriteria(), pageable);
    }

    public FraudCaseDocument getCase(String caseId) {
        return fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new FraudCaseNotFoundException(caseId));
    }

    public Page<FraudCaseSummaryResponse> searchCases(
            FraudCaseStatus status,
            String assignee,
            FraudCasePriority priority,
            RiskLevel riskLevel,
            Instant createdFrom,
            Instant createdTo,
            String linkedAlertId,
            Pageable pageable
    ) {
        return searchRepository.search(
                new FraudCaseSearchCriteria(status, assignee, priority, riskLevel, createdFrom, createdTo, null, null, linkedAlertId),
                pageable
        ).map(responseMapper::toSummary);
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
        FraudCaseWorkQueueCursor cursor = cursorCodec.decode(encodedCursor, effectiveSort);
        var documentSlice = cursor == null
                ? searchRepository.searchSlice(criteria, pageable)
                : searchRepository.searchSliceAfter(criteria, pageable.getPageSize(), effectiveSort, cursor);
        var content = documentSlice.getContent().stream()
                .map(document -> toWorkQueueItem(document, now))
                .toList();
        String nextCursor = documentSlice.hasNext() && !documentSlice.getContent().isEmpty()
                ? cursorCodec.encode(effectiveSort, documentSlice.getContent().getLast())
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

    public List<FraudCaseAuditResponse> auditTrail(String caseId) {
        if (!fraudCaseRepository.existsById(caseId)) {
            throw new FraudCaseNotFoundException(caseId);
        }
        return auditRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .map(this::toAuditResponse)
                .toList();
    }

    private FraudCaseAuditResponse toAuditResponse(FraudCaseAuditEntryDocument document) {
        return new FraudCaseAuditResponse(
                document.getId(),
                document.getCaseId(),
                document.getAction(),
                document.getActorId(),
                document.getOccurredAt(),
                document.getPreviousStatus(),
                document.getNewStatus(),
                document.getDetails() == null ? Map.of() : document.getDetails()
        );
    }

    private FraudCaseSearchCriteria emptyCriteria() {
        return new FraudCaseSearchCriteria(null, null, null, null, null, null, null, null, null);
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
