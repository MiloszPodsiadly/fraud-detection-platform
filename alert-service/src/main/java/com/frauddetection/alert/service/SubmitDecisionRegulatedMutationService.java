package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.exception.AlertNotFoundException;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SubmitDecisionRegulatedMutationService {

    private final AlertRepository alertRepository;
    private final AnalystDecisionStatusMapper analystDecisionStatusMapper;
    private final AnalystActorResolver analystActorResolver;
    private final SubmitDecisionMutationHandler mutationHandler;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;

    public SubmitDecisionRegulatedMutationService(
            AlertRepository alertRepository,
            AnalystDecisionStatusMapper analystDecisionStatusMapper,
            AnalystActorResolver analystActorResolver,
            SubmitDecisionMutationHandler mutationHandler,
            RegulatedMutationCoordinator regulatedMutationCoordinator
    ) {
        this.alertRepository = alertRepository;
        this.analystDecisionStatusMapper = analystDecisionStatusMapper;
        this.analystActorResolver = analystActorResolver;
        this.mutationHandler = mutationHandler;
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
                () -> mutationHandler.applyDecision(alertId, request, resultingStatus, actorId, idempotencyKey, requestHash),
                (saved, state) -> response(saved, request, resultingStatus, publicStatus(state)),
                RegulatedMutationResponseSnapshot::from,
                RegulatedMutationResponseSnapshot::toSubmitDecisionResponse,
                state -> statusResponse(alertId, request, resultingStatus, publicStatus(state))
        );
        return regulatedMutationCoordinator.commit(command).response();
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

    private SubmitAnalystDecisionResponse statusResponse(
            String alertId,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            SubmitDecisionOperationStatus status
    ) {
        return new SubmitAnalystDecisionResponse(
                alertId,
                request.decision(),
                resultingStatus,
                null,
                null,
                status
        );
    }

    private SubmitDecisionOperationStatus publicStatus(RegulatedMutationState state) {
        return switch (state) {
            case REQUESTED, AUDIT_ATTEMPTED -> SubmitDecisionOperationStatus.IN_PROGRESS;
            case BUSINESS_COMMITTING -> SubmitDecisionOperationStatus.COMMIT_UNKNOWN;
            case EVIDENCE_PENDING, EVIDENCE_CONFIRMED, COMMITTED, SUCCESS_AUDIT_RECORDED ->
                    SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
            case COMMITTED_DEGRADED -> SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE;
            case FAILED, BUSINESS_COMMITTED, SUCCESS_AUDIT_PENDING -> SubmitDecisionOperationStatus.RECOVERY_REQUIRED;
            case REJECTED -> SubmitDecisionOperationStatus.REJECTED_BEFORE_MUTATION;
        };
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
