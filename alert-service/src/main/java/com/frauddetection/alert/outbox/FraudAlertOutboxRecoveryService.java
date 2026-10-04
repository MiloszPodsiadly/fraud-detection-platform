package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditMutationRecorder;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class FraudAlertOutboxRecoveryService {

    private final MongoTemplate mongoTemplate;
    private final AuditMutationRecorder auditMutationRecorder;
    private final AlertServiceMetrics metrics;
    private final FraudAlertOutboxBacklogMonitor backlogMonitor;
    private final OutboxOperationalControls operationalControls;
    private final TransactionalOutboxRuntimeReadiness runtimeReadiness;
    private final TransactionTemplate transactionTemplate;

    public FraudAlertOutboxRecoveryService(
            MongoTemplate mongoTemplate,
            AuditMutationRecorder auditMutationRecorder,
            AlertServiceMetrics metrics,
            FraudAlertOutboxBacklogMonitor backlogMonitor,
            OutboxOperationalControls operationalControls,
            TransactionalOutboxRuntimeReadiness runtimeReadiness,
            @Qualifier("mongoTransactionManager") PlatformTransactionManager transactionManager
    ) {
        this.mongoTemplate = mongoTemplate;
        this.auditMutationRecorder = auditMutationRecorder;
        this.metrics = metrics;
        this.backlogMonitor = backlogMonitor;
        this.operationalControls = operationalControls;
        this.runtimeReadiness = runtimeReadiness;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public FraudAlertOutboxBacklogResponse backlog() {
        return backlogMonitor.snapshotAndRecord();
    }

    public FraudAlertOutboxRecordResponse resolveConfirmation(
            String eventId,
            FraudAlertOutboxConfirmationResolutionRequest request,
            String actorId,
            String idempotencyKey
    ) {
        runtimeReadiness.requireReady();
        operationalControls.requireRecoveryEnabled();
        String actor = requireActor(actorId);
        String idempotencyHash = idempotencyHash(eventId, requireIdempotencyKey(idempotencyKey));
        requireResolutionEvidence(request);
        String requestHash = requestHash(eventId, request, actor);
        return auditMutationRecorder.record(
                AuditAction.RESOLVE_FRAUD_ALERT_OUTBOX_CONFIRMATION,
                AuditResourceType.FRAUD_ALERT_OUTBOX,
                eventId,
                null,
                actor,
                () -> transactionTemplate.execute(status ->
                        resolveAuthoritativeRecord(eventId, request, actor, idempotencyHash, requestHash)
                )
        );
    }

    private FraudAlertOutboxRecordResponse resolveAuthoritativeRecord(
            String eventId,
            FraudAlertOutboxConfirmationResolutionRequest request,
            String actor,
            String idempotencyHash,
            String requestHash
    ) {
        FraudAlertOutboxRecord record = mongoTemplate.findById(eventId, FraudAlertOutboxRecord.class);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown fraud alert outbox event");
        }
        FraudAlertOutboxResolutionRecord previousResolution = mongoTemplate.findById(
                idempotencyHash,
                FraudAlertOutboxResolutionRecord.class
        );
        if (previousResolution != null) {
            return identicalReplay(previousResolution, requestHash, actor);
        }
        boolean confirmationUnknown = record.getStatus() == FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN;
        boolean terminalNonDelivery = record.getStatus() == FraudAlertOutboxStatus.FAILED_TERMINAL
                && request.resolution() == FraudAlertOutboxConfirmationResolution.CONFIRMED_NOT_DELIVERED;
        if (!confirmationUnknown && !terminalNonDelivery) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fraud alert outbox event is not reconcilable");
        }

        Instant now = Instant.now();
        ResolutionEvidenceReference evidence = request.evidenceReference();
        Update update = new Update()
                .set("resolution", request.resolution().name())
                .set("resolutionIdempotencyHash", idempotencyHash)
                .set("resolutionRequestHash", requestHash)
                .set("resolutionReason", request.reason())
                .set("resolutionEvidenceType", evidence.type().name())
                .set("resolutionEvidenceReference", evidence.reference())
                .set("resolutionEvidenceVerifiedAt", evidence.verifiedAt())
                .set("resolutionEvidenceVerifiedBy", evidence.verifiedBy())
                .set("resolvedBy", actor)
                .set("resolvedAt", now)
                .set("resolutionPreviousAttempts", record.getAttempts())
                .set("updatedAt", now)
                .unset("leaseOwner")
                .unset("leaseToken")
                .unset("leaseExpiresAt")
                .inc("revision", 1L);
        if (request.resolution() == FraudAlertOutboxConfirmationResolution.PUBLISHED) {
            update.set("status", FraudAlertOutboxStatus.PUBLISHED)
                    .set("publishedAt", now)
                    .unset("lastError");
        } else {
            update.set("status", FraudAlertOutboxStatus.PENDING)
                    .set("attempts", 0)
                    .unset("publishedAt")
                    .unset("publishAttemptedAt")
                    .unset("confirmationUnknownAt")
                    .unset("terminalAt")
                    .unset("lastError");
        }
        Query compareAndSet = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(record.getStatus()),
                Criteria.where("revision").is(record.getRevision()),
                Criteria.where("updatedAt").is(record.getUpdatedAt())
        ));
        FraudAlertOutboxRecord resolved = mongoTemplate.findAndModify(
                compareAndSet,
                update,
                FindAndModifyOptions.options().returnNew(true),
                FraudAlertOutboxRecord.class
        );
        if (resolved == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fraud alert outbox state changed concurrently");
        }
        FraudAlertOutboxRecordResponse response = FraudAlertOutboxRecordResponse.from(resolved);
        persistResolutionEvidence(record, request, actor, idempotencyHash, requestHash, now, response);
        metrics.recordFraudAlertOutboxResolution(request.resolution().name());
        backlogMonitor.snapshotAndRecord();
        return response;
    }

    private void requireResolutionEvidence(FraudAlertOutboxConfirmationResolutionRequest request) {
        if (request == null || request.resolution() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fraud alert outbox resolution is required");
        }
        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid fraud alert outbox resolution reason is required");
        }
        ResolutionEvidenceReference evidence = ResolutionEvidenceReference.require(
                request.evidenceReference(),
                "fraud alert outbox resolution evidence is required"
        );
        ResolutionEvidenceType expected = request.resolution() == FraudAlertOutboxConfirmationResolution.PUBLISHED
                ? ResolutionEvidenceType.BROKER_OFFSET
                : ResolutionEvidenceType.BROKER_NON_DELIVERY;
        if (evidence.type() != expected) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    expected == ResolutionEvidenceType.BROKER_OFFSET
                            ? "broker offset evidence is required"
                            : "verified broker non-delivery evidence is required"
            );
        }
        if (evidence.verifiedAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolution evidence cannot be verified in the future");
        }
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank() || actorId.length() > 120) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated operator is required");
        }
        return actorId.trim();
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid idempotency key is required");
        }
        return idempotencyKey.trim();
    }

    private FraudAlertOutboxRecordResponse identicalReplay(
            FraudAlertOutboxResolutionRecord resolution,
            String requestHash,
            String actor
    ) {
        boolean identical = requestHash.equals(resolution.getRequestHash())
                && actor.equals(resolution.getResolvedBy())
                && resolution.getResponseSnapshot() != null;
        if (!identical) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency key was already used for another resolution");
        }
        return resolution.getResponseSnapshot();
    }

    private String requestHash(
            String eventId,
            FraudAlertOutboxConfirmationResolutionRequest request,
            String actor
    ) {
        ResolutionEvidenceReference evidence = request.evidenceReference();
        return RegulatedMutationIntentHasher.hash(
                "eventId=" + RegulatedMutationIntentHasher.canonicalValue(eventId)
                        + "|resolution=" + request.resolution().name()
                        + "|reason=" + RegulatedMutationIntentHasher.canonicalValue(request.reason())
                        + "|evidenceType=" + evidence.type().name()
                        + "|evidenceReference=" + RegulatedMutationIntentHasher.canonicalValue(evidence.reference())
                        + "|evidenceVerifiedAt=" + evidence.verifiedAt()
                        + "|evidenceVerifiedBy=" + RegulatedMutationIntentHasher.canonicalValue(evidence.verifiedBy())
                        + "|actor=" + RegulatedMutationIntentHasher.canonicalValue(actor)
        );
    }

    private String idempotencyHash(String eventId, String idempotencyKey) {
        return RegulatedMutationIntentHasher.hash(
                "eventId=" + RegulatedMutationIntentHasher.canonicalValue(eventId)
                        + "|idempotencyKey=" + RegulatedMutationIntentHasher.canonicalValue(idempotencyKey)
        );
    }

    private void persistResolutionEvidence(
            FraudAlertOutboxRecord record,
            FraudAlertOutboxConfirmationResolutionRequest request,
            String actor,
            String resolutionId,
            String requestHash,
            Instant resolvedAt,
            FraudAlertOutboxRecordResponse responseSnapshot
    ) {
        ResolutionEvidenceReference evidence = request.evidenceReference();
        FraudAlertOutboxResolutionRecord resolution = new FraudAlertOutboxResolutionRecord();
        resolution.setResolutionId(resolutionId);
        resolution.setEventId(record.getEventId());
        resolution.setPreviousStatus(record.getStatus());
        resolution.setResolution(request.resolution());
        resolution.setReason(request.reason());
        resolution.setEvidenceType(evidence.type().name());
        resolution.setEvidenceReference(evidence.reference());
        resolution.setEvidenceVerifiedAt(evidence.verifiedAt());
        resolution.setEvidenceVerifiedBy(evidence.verifiedBy());
        resolution.setResolvedBy(actor);
        resolution.setResolvedAt(resolvedAt);
        resolution.setRequestHash(requestHash);
        resolution.setResponseSnapshot(responseSnapshot);
        mongoTemplate.insert(resolution);
    }
}
