package com.frauddetection.alert.service;

import com.frauddetection.alert.api.FraudCaseAuditResponse;
import com.frauddetection.alert.api.FraudCaseSummaryResponse;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.fraudcase.FraudCaseSearchCriteria;
import com.frauddetection.alert.fraudcase.FraudCaseSearchRepository;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.persistence.FraudCaseAuditEntryDocument;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class FraudCaseQueryService {

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudCaseAuditRepository auditRepository;
    private final FraudCaseSearchRepository searchRepository;
    private final FraudCaseResponseMapper responseMapper;

    public FraudCaseQueryService(
            FraudCaseRepository fraudCaseRepository,
            FraudCaseAuditRepository auditRepository,
            FraudCaseSearchRepository searchRepository,
            FraudCaseResponseMapper responseMapper
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.auditRepository = auditRepository;
        this.searchRepository = searchRepository;
        this.responseMapper = responseMapper;
    }

    @Deprecated(forRemoval = false)
    public List<FraudCaseDocument> listCases() {
        return fraudCaseRepository.findAll();
    }

    public Page<FraudCaseDocument> listCases(Pageable pageable) {
        return fraudCaseRepository.findAll(pageable);
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
                new FraudCaseSearchCriteria(status, assignee, priority, riskLevel, createdFrom, createdTo, linkedAlertId),
                pageable
        ).map(responseMapper::toSummary);
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
}
