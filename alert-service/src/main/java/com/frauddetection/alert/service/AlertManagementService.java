package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditMutationRecorder;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.PostCommitAuditDegradedException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final AuditMutationRecorder auditMutationRecorder;
    private final AnalystActorResolver analystActorResolver;
    private final AlertServiceMetrics metrics;
    private final AuditDegradationService auditDegradationService;
    private final boolean bankModeFailClosed;

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
            AuditMutationRecorder auditMutationRecorder,
            AnalystActorResolver analystActorResolver,
            AlertServiceMetrics metrics
    ) {
        this(alertRepository, alertDocumentMapper, fraudAlertEventMapper, fraudDecisionEventMapper, alertCaseFactory,
                analystDecisionStatusMapper, fraudAlertEventPublisher, fraudDecisionEventPublisher, fraudCaseManagementService,
                auditMutationRecorder, analystActorResolver, metrics, null, false);
    }

    @Autowired
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
            AuditMutationRecorder auditMutationRecorder,
            AnalystActorResolver analystActorResolver,
            AlertServiceMetrics metrics,
            AuditDegradationService auditDegradationService,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
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
        this.auditMutationRecorder = auditMutationRecorder;
        this.analystActorResolver = analystActorResolver;
        this.metrics = metrics;
        this.auditDegradationService = auditDegradationService;
        this.bankModeFailClosed = bankModeFailClosed;
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
        AlertDocument document = alertRepository.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        AlertStatus resultingStatus = analystDecisionStatusMapper.toAlertStatus(request);
        String actorId = analystActorResolver.resolveActorId(request.analystId(), "SUBMIT_ANALYST_DECISION", alertId);
        String requestHash = requestHash(request);
        SubmitAnalystDecisionResponse replay = replayIfIdempotent(document, request, resultingStatus, idempotencyKey, requestHash);
        if (replay != null) {
            return replay;
        }

        try {
            AlertDocument saved = auditMutationRecorder.record(
                    AuditAction.SUBMIT_ANALYST_DECISION,
                    AuditResourceType.ALERT,
                    document.getAlertId(),
                    document.getCorrelationId(),
                    actorId,
                    () -> saveDecisionWithOutbox(document, request, resultingStatus, actorId, idempotencyKey, requestHash, SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING)
            );
            SubmitDecisionOperationStatus finalStatus = upgradeDecisionOperationStatus(saved, SubmitDecisionOperationStatus.COMMITTED_FULLY_ANCHORED);
            metrics.recordAnalystDecisionSubmitted();
            return response(saved, request, resultingStatus, finalStatus);
        } catch (PostCommitAuditDegradedException exception) {
            AlertDocument saved = exception.result();
            recordPostCommitDegraded(
                    AuditAction.SUBMIT_ANALYST_DECISION,
                    AuditResourceType.ALERT,
                    saved.getAlertId(),
                    "POST_COMMIT_AUDIT_DEGRADED"
            );
            metrics.recordPostCommitAuditDegraded(AuditAction.SUBMIT_ANALYST_DECISION.name());
            if (bankModeFailClosed) {
                throw new AuditPersistenceUnavailableException();
            }
            SubmitDecisionOperationStatus finalStatus = SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE;
            saved.setDecisionOperationStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE.name());
            try {
                alertRepository.save(saved);
            } catch (DataAccessException persistenceException) {
                log.warn("Post-commit audit degraded status persistence failed: reason=POST_COMMIT_AUDIT_DEGRADED");
                saved.setDecisionOperationStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING.name());
                finalStatus = SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
            }
            metrics.recordAnalystDecisionSubmitted();
            return response(saved, request, resultingStatus, finalStatus);
        }
    }

    private AlertDocument saveDecisionWithOutbox(
            AlertDocument document,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            String actorId,
            String idempotencyKey,
            String requestHash,
            SubmitDecisionOperationStatus operationStatus
    ) {
        document.setAlertStatus(resultingStatus);
        document.setAnalystDecision(request.decision());
        document.setAnalystId(actorId);
        document.setDecisionReason(request.decisionReason());
        document.setDecisionTags(request.tags());
        document.setDecidedAt(Instant.now());
        document.setDecisionIdempotencyKey(normalizeIdempotencyKey(idempotencyKey));
        document.setDecisionIdempotencyRequestHash(requestHash);
        document.setDecisionOperationStatus(operationStatus.name());
        AlertCase alertCase = alertDocumentMapper.toDomain(document);
        FraudDecisionEvent event = fraudDecisionEventMapper.toEvent(alertCase, request, resultingStatus, actorId);
        document.setDecisionOutboxEvent(event);
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
        document.setDecisionOutboxAttempts(0);
        document.setDecisionOutboxLeaseOwner(null);
        document.setDecisionOutboxLeaseExpiresAt(null);
        document.setDecisionOutboxLastError(null);
        document.setDecisionOutboxFailureReason(null);
        document.setDecisionOutboxPublishedAt(null);
        return alertRepository.save(document);
    }

    private SubmitDecisionOperationStatus upgradeDecisionOperationStatus(
            AlertDocument saved,
            SubmitDecisionOperationStatus status
    ) {
        saved.setDecisionOperationStatus(status.name());
        try {
            alertRepository.save(saved);
            return status;
        } catch (DataAccessException exception) {
            log.warn("Decision operation status upgrade failed: reason=DECISION_STATUS_UPGRADE_FAILED");
            metrics.recordPostCommitAuditDegraded(AuditAction.SUBMIT_ANALYST_DECISION.name());
            recordPostCommitDegraded(
                    AuditAction.SUBMIT_ANALYST_DECISION,
                    AuditResourceType.ALERT,
                    saved.getAlertId(),
                    "DECISION_STATUS_UPGRADE_FAILED"
            );
            saved.setDecisionOperationStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING.name());
            return SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
        }
    }

    private SubmitAnalystDecisionResponse replayIfIdempotent(
            AlertDocument document,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            String idempotencyKey,
            String requestHash
    ) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey == null || document.getDecisionIdempotencyKey() == null) {
            return null;
        }
        if (!normalizedKey.equals(document.getDecisionIdempotencyKey())) {
            return null;
        }
        if (!requestHash.equals(document.getDecisionIdempotencyRequestHash())) {
            throw new ConflictingIdempotencyKeyException();
        }
        SubmitDecisionOperationStatus status = parseOperationStatus(document.getDecisionOperationStatus());
        String eventId = document.getDecisionOutboxEvent() == null ? null : document.getDecisionOutboxEvent().eventId();
        return new SubmitAnalystDecisionResponse(
                document.getAlertId(),
                request.decision(),
                resultingStatus,
                eventId,
                document.getDecidedAt(),
                status
        );
    }

    private void recordPostCommitDegraded(
            AuditAction operation,
            AuditResourceType resourceType,
            String resourceId,
            String reason
    ) {
        if (auditDegradationService != null) {
            auditDegradationService.recordPostCommitDegraded(operation, resourceType, resourceId, reason);
        }
    }

    private SubmitAnalystDecisionResponse response(
            AlertDocument saved,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            SubmitDecisionOperationStatus status
    ) {
        FraudDecisionEvent event = saved.getDecisionOutboxEvent();
        return new SubmitAnalystDecisionResponse(
                saved.getAlertId(),
                request.decision(),
                resultingStatus,
                event == null ? null : event.eventId(),
                saved.getDecidedAt(),
                status
        );
    }

    private SubmitDecisionOperationStatus parseOperationStatus(String value) {
        if (value == null || value.isBlank()) {
            return SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
        }
        if ("COMMITTED_AUDIT_PENDING".equals(value)) {
            return SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
        }
        if ("COMMITTED_AUDIT_INCOMPLETE".equals(value)) {
            return SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE;
        }
        return SubmitDecisionOperationStatus.valueOf(value);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }

    private String requestHash(SubmitAnalystDecisionRequest request) {
        String canonical = "analystId=" + canonicalValue(request.analystId())
                + "|decision=" + canonicalValue(request.decision())
                + "|decisionReason=" + canonicalValue(request.decisionReason())
                + "|tags=" + canonicalValue(request.tags())
                + "|decisionMetadata=" + canonicalValue(request.decisionMetadata());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    private String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> canonicalValue(entry.getKey()) + ":" + canonicalValue(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object current : iterable) {
                if (!first) {
                    builder.append(",");
                }
                builder.append(canonicalValue(current));
                first = false;
            }
            return builder.append("]").toString();
        }
        return String.valueOf(value);
    }
}
