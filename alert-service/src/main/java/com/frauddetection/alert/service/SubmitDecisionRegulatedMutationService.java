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
import com.frauddetection.alert.regulated.RegulatedMutationIntent;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.submitdecision.SubmitDecisionMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SubmitDecisionRegulatedMutationService {

    private final AlertRepository alertRepository;
    private final AnalystDecisionStatusMapper analystDecisionStatusMapper;
    private final AnalystActorResolver analystActorResolver;
    private final SubmitDecisionMutationHandler mutationHandler;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final boolean evidenceGatedFinalizeEnabled;
    private final boolean submitDecisionEvidenceGatedFinalizeEnabled;

    public SubmitDecisionRegulatedMutationService(
            AlertRepository alertRepository,
            AnalystDecisionStatusMapper analystDecisionStatusMapper,
            AnalystActorResolver analystActorResolver,
            SubmitDecisionMutationHandler mutationHandler,
            RegulatedMutationCoordinator regulatedMutationCoordinator
    ) {
        this(alertRepository, analystDecisionStatusMapper, analystActorResolver, mutationHandler,
                regulatedMutationCoordinator, false, false);
    }

    @Autowired
    public SubmitDecisionRegulatedMutationService(
            AlertRepository alertRepository,
            AnalystDecisionStatusMapper analystDecisionStatusMapper,
            AnalystActorResolver analystActorResolver,
            SubmitDecisionMutationHandler mutationHandler,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            @Value("${app.regulated-mutations.evidence-gated-finalize.enabled:false}") boolean evidenceGatedFinalizeEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled:false}") boolean submitDecisionEvidenceGatedFinalizeEnabled
    ) {
        this.alertRepository = alertRepository;
        this.analystDecisionStatusMapper = analystDecisionStatusMapper;
        this.analystActorResolver = analystActorResolver;
        this.mutationHandler = mutationHandler;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.evidenceGatedFinalizeEnabled = evidenceGatedFinalizeEnabled;
        this.submitDecisionEvidenceGatedFinalizeEnabled = submitDecisionEvidenceGatedFinalizeEnabled;
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
        boolean evidenceGatedFinalize = evidenceGatedFinalizeEnabled && submitDecisionEvidenceGatedFinalizeEnabled;
        RegulatedMutationModelVersion modelVersion = evidenceGatedFinalize
                ? RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
                : RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION;
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
                        evidenceGatedFinalize
                                ? SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                                : SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
                ),
                (saved, state) -> response(saved, request, resultingStatus, publicStatus(state)),
                RegulatedMutationResponseSnapshot::from,
                RegulatedMutationResponseSnapshot::toSubmitDecisionResponse,
                state -> evidenceGatedFinalize
                        ? evidenceGatedStatusResponse(current, publicStatus(state))
                        : statusResponse(alertId, request, resultingStatus, publicStatus(state)),
                intent,
                modelVersion
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

    private SubmitAnalystDecisionResponse evidenceGatedStatusResponse(
            AlertDocument current,
            SubmitDecisionOperationStatus status
    ) {
        return new SubmitAnalystDecisionResponse(
                current.getAlertId(),
                current.getAnalystDecision(),
                current.getAlertStatus(),
                current.getDecisionOutboxEvent() == null ? null : current.getDecisionOutboxEvent().eventId(),
                current.getDecidedAt(),
                status
        );
    }

    private SubmitDecisionOperationStatus publicStatus(RegulatedMutationState state) {
        return switch (state) {
            case REQUESTED, AUDIT_ATTEMPTED -> SubmitDecisionOperationStatus.IN_PROGRESS;
            case EVIDENCE_PREPARING -> SubmitDecisionOperationStatus.EVIDENCE_PREPARING;
            case EVIDENCE_PREPARED -> SubmitDecisionOperationStatus.EVIDENCE_PREPARED;
            case FINALIZING -> SubmitDecisionOperationStatus.FINALIZING;
            case FINALIZED_VISIBLE -> SubmitDecisionOperationStatus.FINALIZED_VISIBLE;
            case FINALIZED_EVIDENCE_PENDING_EXTERNAL -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL;
            case FINALIZED_EVIDENCE_CONFIRMED -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED;
            case REJECTED_EVIDENCE_UNAVAILABLE -> SubmitDecisionOperationStatus.REJECTED_EVIDENCE_UNAVAILABLE;
            case FAILED_BUSINESS_VALIDATION -> SubmitDecisionOperationStatus.FAILED_BUSINESS_VALIDATION;
            case FINALIZE_RECOVERY_REQUIRED -> SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED;
            case BUSINESS_COMMITTING -> SubmitDecisionOperationStatus.COMMIT_UNKNOWN;
            case EVIDENCE_PENDING, COMMITTED, SUCCESS_AUDIT_RECORDED ->
                    SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING;
            case EVIDENCE_CONFIRMED -> SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED;
            case COMMITTED_DEGRADED -> SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE;
            case FAILED, BUSINESS_COMMITTED, SUCCESS_AUDIT_PENDING -> SubmitDecisionOperationStatus.RECOVERY_REQUIRED;
            case REJECTED -> SubmitDecisionOperationStatus.REJECTED_BEFORE_MUTATION;
        };
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
