package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.recovery.OutboxBacklogReader;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationIntent;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OutboxRecoveryService {

    private final OutboxPublisherCoordinator publisherCoordinator;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final OutboxConfirmationResolutionMutationHandler resolutionMutationHandler;
    private final OutboxOperationalControls operationalControls;
    private final OutboxBacklogReader backlogReader;
    private final OutboxStaleLeaseRecovery staleLeaseRecovery;
    private final OutboxProjectionReconciler projectionReconciler;

    @Autowired
    public OutboxRecoveryService(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxPublisherCoordinator publisherCoordinator,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            OutboxConfirmationResolutionMutationHandler resolutionMutationHandler,
            AlertServiceMetrics metrics,
            @Value("${app.outbox.recovery.stale-processing-threshold:PT2M}") Duration staleProcessingThreshold,
            OutboxOperationalControls operationalControls,
            OutboxBacklogReader backlogReader
    ) {
        this.publisherCoordinator = publisherCoordinator;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.resolutionMutationHandler = resolutionMutationHandler;
        Duration effectiveStaleProcessingThreshold = staleProcessingThreshold == null
                ? Duration.ofMinutes(2)
                : staleProcessingThreshold;
        this.operationalControls = operationalControls;
        this.backlogReader = backlogReader;
        this.staleLeaseRecovery = new OutboxStaleLeaseRecovery(
                repository,
                mongoTemplate,
                publisherCoordinator,
                effectiveStaleProcessingThreshold
        );
        this.projectionReconciler = new OutboxProjectionReconciler(repository, mongoTemplate, metrics);
    }

    public OutboxRecoveryService(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxPublisherCoordinator publisherCoordinator,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            OutboxConfirmationResolutionMutationHandler resolutionMutationHandler,
            AlertServiceMetrics metrics,
            Duration staleProcessingThreshold,
            OutboxOperationalControls operationalControls
    ) {
        this(
                repository,
                mongoTemplate,
                publisherCoordinator,
                regulatedMutationCoordinator,
                resolutionMutationHandler,
                metrics,
                staleProcessingThreshold,
                operationalControls,
                new OutboxBacklogReader(repository, metrics)
        );
    }

    public OutboxRecoveryService(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxPublisherCoordinator publisherCoordinator,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            OutboxConfirmationResolutionMutationHandler resolutionMutationHandler,
            AlertServiceMetrics metrics,
            Duration staleProcessingThreshold
    ) {
        this(
                repository,
                mongoTemplate,
                publisherCoordinator,
                regulatedMutationCoordinator,
                resolutionMutationHandler,
                metrics,
                staleProcessingThreshold,
                new OutboxOperationalControls(true, true)
        );
    }

    public OutboxBacklogResponse backlog() {
        return backlogReader.read();
    }

    public OutboxRecoveryRunResponse recoverNow() {
        if (!operationalControls.recoveryEnabled()) {
            return new OutboxRecoveryRunResponse(0, 0, 0, 0);
        }
        OutboxStaleLeaseRecovery.Result leaseRecovery = staleLeaseRecovery.recover();
        int repaired = projectionReconciler.reconcile();
        int attempted = publisherCoordinator.publishPending(100);
        return new OutboxRecoveryRunResponse(
                leaseRecovery.releasedStaleProcessing(),
                leaseRecovery.markedConfirmationUnknown(),
                repaired,
                attempted
        );
    }

    public OutboxRecordResponse resolveConfirmation(
            String eventId,
            OutboxConfirmationResolutionRequest request,
            String actorId,
            String idempotencyKey
    ) {
        operationalControls.requireRecoveryEnabled();
        String authenticatedActor = requireAuthenticatedActor(actorId);
        String requestHash = RegulatedMutationIntentHasher.hash("eventId=" + eventId
                + "|resolution=" + request.resolution()
                + "|pendingRequestId=" + RegulatedMutationIntentHasher.canonicalValue(request.pendingRequestId())
                + "|reason=" + RegulatedMutationIntentHasher.canonicalValue(request.reason())
                + "|evidence=" + RegulatedMutationIntentHasher.canonicalValue(request.evidenceReference()));
        RegulatedMutationCommand<TransactionalOutboxRecordDocument, OutboxRecordResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                authenticatedActor,
                eventId,
                AuditResourceType.DECISION_OUTBOX,
                AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION,
                null,
                requestHash,
                context -> resolutionMutationHandler.resolve(eventId, request, authenticatedActor),
                (record, state) -> OutboxRecordResponse.from(record),
                RegulatedMutationResponseSnapshot::from,
                RegulatedMutationResponseSnapshot::toOutboxRecordResponse,
                state -> statusResponse(eventId, state),
                resolutionIntent(eventId, request, authenticatedActor, requestHash),
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
        RegulatedMutationResult<OutboxRecordResponse> result = regulatedMutationCoordinator.commit(command);
        return result.response().withOperationStatus(result.state().name());
    }

    boolean completeProjectionRepair(
            TransactionalOutboxRecordDocument record,
            String repairToken
    ) {
        return projectionReconciler.complete(record, repairToken);
    }

    private OutboxRecordResponse statusResponse(String eventId, RegulatedMutationState state) {
        return new OutboxRecordResponse(
                eventId,
                null,
                null,
                AuditResourceType.DECISION_OUTBOX.name(),
                null,
                "FRAUD_DECISION",
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                state.name()
        );
    }

    private RegulatedMutationIntent resolutionIntent(
            String eventId,
            OutboxConfirmationResolutionRequest request,
            String actorId,
            String payloadHash
    ) {
        String reasonHash = RegulatedMutationIntentHasher.hash(request.reason());
        String intentHash = RegulatedMutationIntentHasher.hash(
                "resourceId=" + RegulatedMutationIntentHasher.canonicalValue(eventId)
                        + "|action=" + AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION.name()
                        + "|actorId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                        + "|resolution=" + RegulatedMutationIntentHasher.canonicalValue(request.resolution())
                        + "|pendingRequestId=" + RegulatedMutationIntentHasher.canonicalValue(request.pendingRequestId())
                        + "|reasonHash=" + reasonHash
                        + "|payloadHash=" + payloadHash
        );
        return new RegulatedMutationIntent(
                intentHash,
                eventId,
                AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION.name(),
                actorId,
                null,
                reasonHash,
                null,
                request.resolution() == null ? null : request.resolution().name(),
                null,
                reasonHash,
                payloadHash
        );
    }

    private String requireAuthenticatedActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "authenticated actor is required"
            );
        }
        return actorId.trim();
    }

}
