package com.frauddetection.alert.regulated.mutation.submitdecision;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.exception.AlertNotFoundException;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.service.DecisionOutboxWriter;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SubmitDecisionMutationHandler {

    private final AlertRepository alertRepository;
    private final AlertDocumentMapper alertDocumentMapper;
    private final DecisionOutboxWriter decisionOutboxWriter;

    public SubmitDecisionMutationHandler(
            AlertRepository alertRepository,
            AlertDocumentMapper alertDocumentMapper,
            DecisionOutboxWriter decisionOutboxWriter
    ) {
        this.alertRepository = alertRepository;
        this.alertDocumentMapper = alertDocumentMapper;
        this.decisionOutboxWriter = decisionOutboxWriter;
    }

    public AlertDocument applyDecision(
            String alertId,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            String actorId,
            String idempotencyKey,
            String requestHash,
            String mutationCommandId
    ) {
        return applyDecision(
                alertId,
                request,
                resultingStatus,
                actorId,
                idempotencyKey,
                requestHash,
                mutationCommandId,
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
        );
    }

    public AlertDocument applyDecision(
            String alertId,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            String actorId,
            String idempotencyKey,
            String requestHash,
            String mutationCommandId,
            SubmitDecisionOperationStatus operationStatus
    ) {
        AlertDocument document = alertRepository.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        document.setAlertStatus(resultingStatus);
        document.setAnalystDecision(request.decision());
        document.setAnalystId(actorId);
        document.setDecisionReason(request.decisionReason());
        document.setDecisionTags(request.tags());
        document.setDecidedAt(Instant.now());
        document.setDecisionIdempotencyKey(normalizeIdempotencyKey(idempotencyKey));
        document.setDecisionIdempotencyRequestHash(requestHash);
        document.setDecisionOperationStatus(operationStatus.name());
        decisionOutboxWriter.attachPendingOutbox(
                document,
                alertDocumentMapper.toDomain(document),
                request,
                resultingStatus,
                actorId,
                mutationCommandId
        );
        return alertRepository.save(document);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }
}
