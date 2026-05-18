package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.evidence.AlertEvidenceSnapshotProjectionService;
import com.frauddetection.alert.exception.AlertNotFoundException;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudAlertEventMapper;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.suspicious.SuspiciousTransactionProjectionService;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AlertManagementService implements AlertManagementUseCase {

    private static final Logger log = LoggerFactory.getLogger(AlertManagementService.class);

    private final AlertRepository alertRepository;
    private final AlertDocumentMapper alertDocumentMapper;
    private final FraudAlertEventMapper fraudAlertEventMapper;
    private final AlertCaseFactory alertCaseFactory;
    private final AlertEvidenceSnapshotProjectionService evidenceSnapshotProjectionService;
    private final FraudAlertEventPublisher fraudAlertEventPublisher;
    private final FraudCaseManagementService fraudCaseManagementService;
    private final SuspiciousTransactionProjectionService suspiciousTransactionProjectionService;
    private final AlertServiceMetrics metrics;
    private final SubmitDecisionRegulatedMutationService submitDecisionRegulatedMutationService;

    public AlertManagementService(
            AlertRepository alertRepository,
            AlertDocumentMapper alertDocumentMapper,
            FraudAlertEventMapper fraudAlertEventMapper,
            AlertCaseFactory alertCaseFactory,
            AlertEvidenceSnapshotProjectionService evidenceSnapshotProjectionService,
            FraudAlertEventPublisher fraudAlertEventPublisher,
            FraudCaseManagementService fraudCaseManagementService,
            SuspiciousTransactionProjectionService suspiciousTransactionProjectionService,
            AlertServiceMetrics metrics,
            SubmitDecisionRegulatedMutationService submitDecisionRegulatedMutationService
    ) {
        this.alertRepository = alertRepository;
        this.alertDocumentMapper = alertDocumentMapper;
        this.fraudAlertEventMapper = fraudAlertEventMapper;
        this.alertCaseFactory = alertCaseFactory;
        this.evidenceSnapshotProjectionService = evidenceSnapshotProjectionService;
        this.fraudAlertEventPublisher = fraudAlertEventPublisher;
        this.fraudCaseManagementService = fraudCaseManagementService;
        this.suspiciousTransactionProjectionService = suspiciousTransactionProjectionService;
        this.metrics = metrics;
        this.submitDecisionRegulatedMutationService = submitDecisionRegulatedMutationService;
    }

    @Override
    public void handleScoredTransaction(TransactionScoredEvent event) {
        fraudCaseManagementService.handleScoredTransaction(event);

        if (!isAlertWorthy(event)) {
            projectSuspiciousTransaction(event, null);
            return;
        }

        if (!Boolean.TRUE.equals(event.alertRecommended())) {
            projectSuspiciousTransaction(event, null);
            return;
        }

        if (alertRepository.existsByTransactionId(event.transactionId())) {
            reconcileSuspiciousTransactionWithExistingAlert(event);
            return;
        }

        AlertCase alertCase = alertCaseFactory.from(event);

        AlertDocument saved;
        try {
            AlertDocument document = alertDocumentMapper.toDocument(alertCase);
            // Evidence snapshot projection must not control case lifecycle. Projection failure is represented as ERROR
            // diagnostic so alert creation can continue without creating fake AVAILABLE evidence.
            document.setEvidenceSnapshot(evidenceSnapshotProjectionService.projectOrDiagnostic(event));
            saved = alertRepository.save(document);
        } catch (DuplicateKeyException exception) {
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .log("Skipped duplicate fraud alert.");
            reconcileSuspiciousTransactionWithExistingAlert(event);
            return;
        }

        projectSuspiciousTransaction(event, saved.getAlertId());

        FraudAlertEvent fraudAlertEvent = fraudAlertEventMapper.toEvent(alertDocumentMapper.toDomain(saved));
        fraudAlertEventPublisher.publish(fraudAlertEvent);

        log.atInfo().addKeyValue("alertId", saved.getAlertId()).addKeyValue("transactionId", saved.getTransactionId()).log("Created fraud alert.");
    }

    private void reconcileSuspiciousTransactionWithExistingAlert(TransactionScoredEvent event) {
        String existingAlertId = alertRepository.findByTransactionId(event.transactionId())
                .map(AlertDocument::getAlertId)
                .orElse(null);
        projectSuspiciousTransaction(event, existingAlertId);
    }

    private void projectSuspiciousTransaction(TransactionScoredEvent event, String linkedAlertId) {
        try {
            suspiciousTransactionProjectionService.projectOrUpdate(event, linkedAlertId);
        } catch (RuntimeException exception) {
            metrics.recordSuspiciousTransactionProjectionError("projection_error");
            log.atWarn()
                    .addKeyValue("reason", "projection_error")
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("Suspicious transaction read-model reconciliation failed during alert handling.");
        }
    }

    private boolean isAlertWorthy(TransactionScoredEvent event) {
        return event != null && (Boolean.TRUE.equals(event.alertRecommended())
                || event.riskLevel() == RiskLevel.HIGH
                || event.riskLevel() == RiskLevel.CRITICAL);
    }

    @Override
    public List<AlertCase> listAlerts() {
        return alertRepository.findAll().stream().map(alertDocumentMapper::toDomain).toList();
    }

    @Override
    public Page<AlertCase> listAlerts(Pageable pageable) {
        return alertRepository.findAll(pageable).map(alertDocumentMapper::toDomain);
    }

    @Override
    public AlertCase getAlert(String alertId) {
        return alertDocumentMapper.toDomain(alertRepository.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId)));
    }

    @Override
    public SubmitAnalystDecisionResponse submitDecision(String alertId, SubmitAnalystDecisionRequest request) {
        return submitDecision(alertId, request, null);
    }

    @Override
    public SubmitAnalystDecisionResponse submitDecision(String alertId, SubmitAnalystDecisionRequest request, String idempotencyKey) {
        SubmitAnalystDecisionResponse response = submitDecisionRegulatedMutationService.submit(alertId, request, idempotencyKey);
        metrics.recordAnalystDecisionSubmitted();
        return response;
    }
}
