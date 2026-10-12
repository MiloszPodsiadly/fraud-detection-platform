package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.FraudCaseTransactionDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationIntent;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FraudCaseUpdateCoordinator {

    private final FraudCaseRepository fraudCaseRepository;
    private final AnalystActorResolver analystActorResolver;
    private final FraudCaseUpdateMutationHandler updateMutationHandler;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final FraudCaseResponseMapper responseMapper;

    public FraudCaseUpdateCoordinator(
            FraudCaseRepository fraudCaseRepository,
            AnalystActorResolver analystActorResolver,
            FraudCaseUpdateMutationHandler updateMutationHandler,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            FraudCaseResponseMapper responseMapper
    ) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.analystActorResolver = analystActorResolver;
        this.updateMutationHandler = updateMutationHandler;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.responseMapper = responseMapper;
    }

    public UpdateFraudCaseResponse updateCase(
            String caseId,
            UpdateFraudCaseRequest request,
            String idempotencyKey
    ) {
        FraudCaseDocument document = fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new FraudCaseNotFoundException(caseId));
        String actorId = analystActorResolver.resolveActorId(request.analystId(), "UPDATE_FRAUD_CASE", caseId);
        String canonicalPayload = payload(request, actorId);
        String idempotencyKeyHash = RegulatedMutationIntentHasher.hash(idempotencyKey);
        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.fraudCaseUpdate(
                caseId,
                actorId,
                request.status(),
                actorId,
                request.decisionReason(),
                request.tags(),
                canonicalPayload
        );
        RegulatedMutationCommand<FraudCaseDocument, UpdateFraudCaseResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                caseId,
                AuditResourceType.FRAUD_CASE,
                AuditAction.UPDATE_FRAUD_CASE,
                correlationId(document),
                RegulatedMutationIntentHasher.hash(canonicalPayload),
                context -> updateMutationHandler.update(caseId, request, actorId, context),
                (saved, state) -> committedResponse(saved, idempotencyKeyHash, publicStatus(state)),
                RegulatedMutationResponseSnapshot::fromUpdateFraudCaseResponse,
                RegulatedMutationResponseSnapshot::toUpdateFraudCaseResponse,
                state -> statusResponse(caseId, document, idempotencyKeyHash, state),
                intent,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    private String correlationId(FraudCaseDocument document) {
        return (document.getTransactions() == null
                ? List.<FraudCaseTransactionDocument>of()
                : document.getTransactions()).stream()
                .map(FraudCaseTransactionDocument::getCorrelationId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private UpdateFraudCaseResponse committedResponse(
            FraudCaseDocument saved,
            String idempotencyKeyHash,
            SubmitDecisionOperationStatus status
    ) {
        return new UpdateFraudCaseResponse(
                status,
                null,
                idempotencyKeyHash,
                saved.getCaseId(),
                null,
                responseMapper.toResponse(saved),
                null
        );
    }

    private UpdateFraudCaseResponse statusResponse(
            String caseId,
            FraudCaseDocument current,
            String idempotencyKeyHash,
            RegulatedMutationState state
    ) {
        SubmitDecisionOperationStatus status = publicStatus(state);
        return new UpdateFraudCaseResponse(
                status,
                null,
                idempotencyKeyHash,
                caseId,
                responseMapper.toResponse(current),
                null,
                status == SubmitDecisionOperationStatus.RECOVERY_REQUIRED
                        || status == SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED
                        ? state.name()
                        : null
        );
    }

    private SubmitDecisionOperationStatus publicStatus(RegulatedMutationState state) {
        return switch (state) {
            case REQUESTED -> SubmitDecisionOperationStatus.IN_PROGRESS;
            case EVIDENCE_PREPARING -> SubmitDecisionOperationStatus.EVIDENCE_PREPARING;
            case EVIDENCE_PREPARED -> SubmitDecisionOperationStatus.EVIDENCE_PREPARED;
            case FINALIZING -> SubmitDecisionOperationStatus.FINALIZING;
            case FINALIZED_EVIDENCE_PENDING_EXTERNAL ->
                    SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL;
            case FINALIZED_EVIDENCE_CONFIRMED -> SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED;
            case REJECTED_EVIDENCE_UNAVAILABLE -> SubmitDecisionOperationStatus.REJECTED_EVIDENCE_UNAVAILABLE;
            case FAILED_BUSINESS_VALIDATION -> SubmitDecisionOperationStatus.FAILED_BUSINESS_VALIDATION;
            case FINALIZE_RECOVERY_REQUIRED, FAILED -> SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED;
        };
    }

    private String payload(UpdateFraudCaseRequest request, String actorId) {
        return "status=" + RegulatedMutationIntentHasher.canonicalValue(request.status())
                + "|analystId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                + "|decisionReason=" + RegulatedMutationIntentHasher.canonicalValue(request.decisionReason())
                + "|tags=" + RegulatedMutationIntentHasher.canonicalValue(request.tags());
    }
}
