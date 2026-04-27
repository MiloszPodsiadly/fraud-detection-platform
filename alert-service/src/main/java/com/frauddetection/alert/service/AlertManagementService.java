package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.alert.exception.AlertNotFoundException;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudAlertEventMapper;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.messaging.FraudAlertEventPublisher;
import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AlertManagementService implements AlertManagementUseCase {

    private static final Logger log = LoggerFactory.getLogger(AlertManagementService.class);

    private final AlertRepository alertRepository;
    private final AlertDocumentMapper alertDocumentMapper;
    private final FraudAlertEventMapper fraudAlertEventMapper;
    private final FraudDecisionEventMapper fraudDecisionEventMapper;
    private final AlertCaseFactory alertCaseFactory;
    private final AnalystDecisionStatusMapper analystDecisionStatusMapper;
    private final FraudAlertEventPublisher fraudAlertEventPublisher;
    private final FraudDecisionEventPublisher fraudDecisionEventPublisher;
    private final FraudCaseManagementService fraudCaseManagementService;
    private final AuditService auditService;
    private final AnalystActorResolver analystActorResolver;
    private final AlertServiceMetrics metrics;

    public AlertManagementService(
            AlertRepository alertRepository,
            AlertDocumentMapper alertDocumentMapper,
            FraudAlertEventMapper fraudAlertEventMapper,
            FraudDecisionEventMapper fraudDecisionEventMapper,
            AlertCaseFactory alertCaseFactory,
            AnalystDecisionStatusMapper analystDecisionStatusMapper,
            FraudAlertEventPublisher fraudAlertEventPublisher,
            FraudDecisionEventPublisher fraudDecisionEventPublisher,
            FraudCaseManagementService fraudCaseManagementService,
            AuditService auditService,
            AnalystActorResolver analystActorResolver,
            AlertServiceMetrics metrics
    ) {
        this.alertRepository = alertRepository;
        this.alertDocumentMapper = alertDocumentMapper;
        this.fraudAlertEventMapper = fraudAlertEventMapper;
        this.fraudDecisionEventMapper = fraudDecisionEventMapper;
        this.alertCaseFactory = alertCaseFactory;
        this.analystDecisionStatusMapper = analystDecisionStatusMapper;
        this.fraudAlertEventPublisher = fraudAlertEventPublisher;
        this.fraudDecisionEventPublisher = fraudDecisionEventPublisher;
        this.fraudCaseManagementService = fraudCaseManagementService;
        this.auditService = auditService;
        this.analystActorResolver = analystActorResolver;
        this.metrics = metrics;
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
        AlertDocument document = alertRepository.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        AlertStatus resultingStatus = analystDecisionStatusMapper.toAlertStatus(request);
        String actorId = analystActorResolver.resolveActorId(request.analystId(), "SUBMIT_ANALYST_DECISION", alertId);

        auditService.audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                document.getAlertId(),
                document.getCorrelationId(),
                actorId
        );

        document.setAlertStatus(resultingStatus);
        document.setAnalystDecision(request.decision());
        document.setAnalystId(actorId);
        document.setDecisionReason(request.decisionReason());
        document.setDecisionTags(request.tags());
        document.setDecidedAt(Instant.now());

        AlertDocument saved = alertRepository.save(document);
        AlertCase alertCase = alertDocumentMapper.toDomain(saved);
        FraudDecisionEvent event = fraudDecisionEventMapper.toEvent(alertCase, request, resultingStatus, actorId);
        fraudDecisionEventPublisher.publish(event);
        metrics.recordAnalystDecisionSubmitted();

        return new SubmitAnalystDecisionResponse(alertId, request.decision(), resultingStatus, event.eventId(), event.decidedAt());
    }
}
