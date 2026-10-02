package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.exception.AlertNotFoundException;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCommandRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationIntent;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationPublicStatusMapper;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.submitdecision.SubmitDecisionMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SubmitDecisionRegulatedMutationService {

    private final AlertRepository alertRepository;
    private final AnalystDecisionStatusMapper analystDecisionStatusMapper;
    private final AnalystActorResolver analystActorResolver;
    private final SubmitDecisionMutationHandler mutationHandler;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final RegulatedMutationPublicStatusMapper publicStatusMapper;
    private final TransactionalOutboxRecordRepository outboxRepository;
    private final RegulatedMutationCommandRepository commandRepository;

    public SubmitDecisionRegulatedMutationService(
            AlertRepository alertRepository,
            AnalystDecisionStatusMapper analystDecisionStatusMapper,
            AnalystActorResolver analystActorResolver,
            SubmitDecisionMutationHandler mutationHandler,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            TransactionalOutboxRecordRepository outboxRepository,
            RegulatedMutationCommandRepository commandRepository
    ) {
        this(alertRepository, analystDecisionStatusMapper, analystActorResolver, mutationHandler,
                regulatedMutationCoordinator, new RegulatedMutationPublicStatusMapper(), outboxRepository,
                commandRepository);
    }

    @Autowired
    public SubmitDecisionRegulatedMutationService(
            AlertRepository alertRepository,
            AnalystDecisionStatusMapper analystDecisionStatusMapper,
            AnalystActorResolver analystActorResolver,
            SubmitDecisionMutationHandler mutationHandler,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            RegulatedMutationPublicStatusMapper publicStatusMapper,
            TransactionalOutboxRecordRepository outboxRepository,
            RegulatedMutationCommandRepository commandRepository
    ) {
        this.alertRepository = alertRepository;
        this.analystDecisionStatusMapper = analystDecisionStatusMapper;
        this.analystActorResolver = analystActorResolver;
        this.mutationHandler = mutationHandler;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.publicStatusMapper = publicStatusMapper;
        this.outboxRepository = outboxRepository;
        this.commandRepository = commandRepository;
    }

    public SubmitAnalystDecisionResponse submit(String alertId, SubmitAnalystDecisionRequest request, String idempotencyKey) {
        AlertDocument current = alertRepository.findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
        AlertStatus resultingStatus = analystDecisionStatusMapper.toAlertStatus(request);
        String actorId = analystActorResolver.resolveActorId(request.analystId(), "SUBMIT_ANALYST_DECISION", alertId);
        String requestHash = requestHash(request);
        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.submitDecision(
                alertId,
                actorId,
                request.decision(),
                request.decisionReason(),
                request.tags()
        );
        RegulatedMutationModelVersion modelVersion = RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1;
        RegulatedMutationCommand<AlertDocument, SubmitAnalystDecisionResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                alertId,
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                current.getCorrelationId(),
                requestHash,
                context -> mutationHandler.applyDecision(
                        alertId,
                        request,
                        resultingStatus,
                        actorId,
                        idempotencyKey,
                        requestHash,
                        context.commandId(),
                        SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                ),
                (saved, state) -> response(
                        saved,
                        request,
                        resultingStatus,
                        publicStatus(state, modelVersion),
                        idempotencyKey
                ),
                RegulatedMutationResponseSnapshot::from,
                RegulatedMutationResponseSnapshot::toSubmitDecisionResponse,
                state -> statusResponse(alertId, current, state, modelVersion, idempotencyKey),
                intent,
                modelVersion
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    private SubmitAnalystDecisionResponse response(
            AlertDocument saved,
            SubmitAnalystDecisionRequest request,
            AlertStatus resultingStatus,
            SubmitDecisionOperationStatus status,
            String idempotencyKey
    ) {
        return new SubmitAnalystDecisionResponse(
                saved.getAlertId(),
                request.decision(),
                resultingStatus,
                authoritativeDecisionEventId(saved, idempotencyKey),
                saved.getDecidedAt(),
                status
        );
    }

    private SubmitAnalystDecisionResponse statusResponse(
            String alertId,
            AlertDocument current,
            RegulatedMutationState state,
            RegulatedMutationModelVersion modelVersion,
            String idempotencyKey
    ) {
        SubmitDecisionOperationStatus status = publicStatus(state, modelVersion);
        if (state == RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE
                || state == RegulatedMutationState.FAILED_BUSINESS_VALIDATION) {
            return new SubmitAnalystDecisionResponse(alertId, null, null, null, null, status);
        }
        return evidenceGatedStatusResponse(current, status, idempotencyKey);
    }

    private SubmitAnalystDecisionResponse evidenceGatedStatusResponse(
            AlertDocument current,
            SubmitDecisionOperationStatus status,
            String idempotencyKey
    ) {
        return new SubmitAnalystDecisionResponse(
                current.getAlertId(),
                current.getAnalystDecision(),
                current.getAlertStatus(),
                authoritativeDecisionEventId(current, idempotencyKey),
                current.getDecidedAt(),
                status
        );
    }

    private SubmitDecisionOperationStatus publicStatus(
            RegulatedMutationState state,
            RegulatedMutationModelVersion modelVersion
    ) {
        return publicStatusMapper.submitDecisionStatus(state, modelVersion);
    }

    private String authoritativeDecisionEventId(AlertDocument alert, String idempotencyKey) {
        String projectedEventId = alert.getDecisionOutboxEventId();
        if (projectedEventId == null || projectedEventId.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return commandRepository.findByIdempotencyKey(idempotencyKey.trim())
                .filter(command -> AuditAction.SUBMIT_ANALYST_DECISION.name().equals(command.getAction()))
                .filter(command -> AuditResourceType.ALERT.name().equals(command.getResourceType()))
                .filter(command -> alert.getAlertId().equals(command.getResourceId()))
                .flatMap(command -> outboxRepository.findByMutationCommandId(command.getId())
                        .filter(outbox -> authoritativeForAlert(outbox, alert, command.getId())))
                .map(TransactionalOutboxRecordDocument::getEventId)
                .orElse(null);
    }

    private boolean authoritativeForAlert(
            TransactionalOutboxRecordDocument outbox,
            AlertDocument alert,
            String commandId
    ) {
        return outbox.getEventId() != null
                && outbox.getEventId().equals(alert.getDecisionOutboxEventId())
                && alert.getAlertId().equals(outbox.getResourceId())
                && commandId.equals(outbox.getMutationCommandId());
    }

    private String requestHash(SubmitAnalystDecisionRequest request) {
        String canonical = "analystId=" + RegulatedMutationIntentHasher.canonicalValue(request.analystId())
                + "|decision=" + RegulatedMutationIntentHasher.canonicalValue(request.decision())
                + "|decisionReason=" + RegulatedMutationIntentHasher.canonicalValue(request.decisionReason())
                + "|tags=" + RegulatedMutationIntentHasher.canonicalValue(request.tags())
                + "|decisionMetadata=" + RegulatedMutationIntentHasher.canonicalValue(request.decisionMetadata());
        return RegulatedMutationIntentHasher.hash(canonical);
    }
}
