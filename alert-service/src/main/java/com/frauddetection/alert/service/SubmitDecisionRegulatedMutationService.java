package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.exception.AlertNotFoundException;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SubmitDecisionRegulatedMutationService {

    private final AlertRepository alertRepository;
    private final AlertDocumentMapper alertDocumentMapper;
    private final AnalystDecisionStatusMapper analystDecisionStatusMapper;
    private final AnalystActorResolver analystActorResolver;
    private final DecisionOutboxWriter decisionOutboxWriter;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;

    public SubmitDecisionRegulatedMutationService(
            AlertRepository alertRepository,
            AlertDocumentMapper alertDocumentMapper,
            AnalystDecisionStatusMapper analystDecisionStatusMapper,
            AnalystActorResolver analystActorResolver,
            DecisionOutboxWriter decisionOutboxWriter,
            RegulatedMutationCoordinator regulatedMutationCoordinator
    ) {
        this.alertRepository = alertRepository;
        this.alertDocumentMapper = alertDocumentMapper;
        this.analystDecisionStatusMapper = analystDecisionStatusMapper;
        this.analystActorResolver = analystActorResolver;
        this.decisionOutboxWriter = decisionOutboxWriter;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
    }

    public SubmitAnalystDecisionResponse submit(String alertId, SubmitAnalystDecisionRequest request, String idempotencyKey) {
        AlertDocument current = alertRepository.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        AlertStatus resultingStatus = analystDecisionStatusMapper.toAlertStatus(request);
        String actorId = analystActorResolver.resolveActorId(request.analystId(), "SUBMIT_ANALYST_DECISION", alertId);
        String requestHash = requestHash(request);
        RegulatedMutationCommand<AlertDocument, SubmitAnalystDecisionResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                alertId,
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                current.getCorrelationId(),
                requestHash,
                () -> applyDecision(alertId, request, resultingStatus, actorId, idempotencyKey, requestHash),
                (saved, state) -> response(saved, request, resultingStatus, publicStatus(state)),
                RegulatedMutationResponseSnapshot::from
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    private AlertDocument applyDecision(
            String alertId,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            String actorId,
            String idempotencyKey,
            String requestHash
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
        document.setDecisionOperationStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING.name());
        decisionOutboxWriter.attachPendingOutbox(
                document,
                alertDocumentMapper.toDomain(document),
                request,
                resultingStatus,
                actorId
        );
        return alertRepository.save(document);
    }

    private SubmitAnalystDecisionResponse response(
            AlertDocument saved,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            SubmitDecisionOperationStatus status
    ) {
        if (status != SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING) {
            saved.setDecisionOperationStatus(status.name());
            try {
                alertRepository.save(saved);
            } catch (RuntimeException ignored) {
                status = SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
            }
        }
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

    private SubmitDecisionOperationStatus publicStatus(RegulatedMutationState state) {
        if (state == RegulatedMutationState.COMMITTED_DEGRADED) {
            return SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE;
        }
        return SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
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
