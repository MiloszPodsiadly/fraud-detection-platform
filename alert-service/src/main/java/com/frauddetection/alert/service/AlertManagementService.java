package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.exception.AlertNotFoundException;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudAlertEventMapper;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
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
    private final FraudAlertEventPublisher fraudAlertEventPublisher;
    private final FraudCaseManagementService fraudCaseManagementService;
    private final AlertServiceMetrics metrics;
    private final SubmitDecisionRegulatedMutationService submitDecisionRegulatedMutationService;

    public AlertManagementService(
            AlertRepository alertRepository,
            AlertDocumentMapper alertDocumentMapper,
            FraudAlertEventMapper fraudAlertEventMapper,
            AlertCaseFactory alertCaseFactory,
            FraudAlertEventPublisher fraudAlertEventPublisher,
            FraudCaseManagementService fraudCaseManagementService,
            AlertServiceMetrics metrics,
            SubmitDecisionRegulatedMutationService submitDecisionRegulatedMutationService
    ) {
        this.alertRepository = alertRepository;
        this.alertDocumentMapper = alertDocumentMapper;
        this.fraudAlertEventMapper = fraudAlertEventMapper;
        this.alertCaseFactory = alertCaseFactory;
        this.fraudAlertEventPublisher = fraudAlertEventPublisher;
        this.fraudCaseManagementService = fraudCaseManagementService;
        this.metrics = metrics;
        this.submitDecisionRegulatedMutationService = submitDecisionRegulatedMutationService;
    }

    @Override
    public void handleScoredTransaction(TransactionScoredEvent event) {
        fraudCaseManagementService.handleScoredTransaction(event);

        if (!Boolean.TRUE.equals(event.alertRecommended()) || alertRepository.existsByTransactionId(event.transactionId())) {
            return;
        }

        AlertCase alertCase = alertCaseFactory.from(event);

        AlertDocument saved;
        try {
            saved = alertRepository.save(alertDocumentMapper.toDocument(alertCase));
        } catch (DuplicateKeyException exception) {
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .log("Skipped duplicate fraud alert.");
            return;
        }

        FraudAlertEvent fraudAlertEvent = fraudAlertEventMapper.toEvent(alertDocumentMapper.toDomain(saved));
        fraudAlertEventPublisher.publish(fraudAlertEvent);

        log.atInfo().addKeyValue("alertId", saved.getAlertId()).addKeyValue("transactionId", saved.getTransactionId()).log("Created fraud alert.");
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
