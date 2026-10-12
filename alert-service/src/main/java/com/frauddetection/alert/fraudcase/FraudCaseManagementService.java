package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.FraudCaseWorkQueueSliceResponse;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class FraudCaseManagementService {

    private final FraudCaseIngestionService ingestionService;
    private final FraudCaseUpdateCoordinator updateCoordinator;
    private final FraudCaseQueryService queryService;

    public FraudCaseManagementService(
            FraudCaseIngestionService ingestionService,
            FraudCaseUpdateCoordinator updateCoordinator,
            FraudCaseQueryService queryService
    ) {
        this.ingestionService = ingestionService;
        this.updateCoordinator = updateCoordinator;
        this.queryService = queryService;
    }

    public void handleScoredTransaction(TransactionScoredEvent event) {
        ingestionService.handleScoredTransaction(event);
    }

    public FraudCaseDocument getCase(String caseId) {
        return queryService.getCase(caseId);
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
        return queryService.workQueue(
                status,
                assignee,
                priority,
                riskLevel,
                createdFrom,
                createdTo,
                updatedFrom,
                updatedTo,
                linkedAlertId,
                pageable
        );
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
            String cursor,
            Sort.Order sortOrder
    ) {
        return queryService.workQueue(
                status,
                assignee,
                priority,
                riskLevel,
                createdFrom,
                createdTo,
                updatedFrom,
                updatedTo,
                linkedAlertId,
                pageable,
                cursor,
                sortOrder
        );
    }

    public UpdateFraudCaseResponse updateCase(
            String caseId,
            UpdateFraudCaseRequest request,
            String idempotencyKey
    ) {
        return updateCoordinator.updateCase(caseId, request, idempotencyKey);
    }
}
