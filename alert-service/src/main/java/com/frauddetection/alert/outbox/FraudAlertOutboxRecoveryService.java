package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditMutationRecorder;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxService;
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
import java.util.Objects;

@Service
public class FraudAlertOutboxRecoveryService {

    private final MongoTemplate mongoTemplate;
    private final AuditMutationRecorder auditMutationRecorder;
    private final AlertServiceMetrics metrics;
    private final FraudAlertOutboxBacklogMonitor backlogMonitor;
    private final OutboxOperationalControls operationalControls;
    private final TransactionalOutboxRuntimeReadiness runtimeReadiness;
    private final FraudAlertPublicationEvidenceVerifier publicationEvidenceVerifier;
    private final WriteActionAuditOutboxService auditOutboxService;
    private final TransactionTemplate transactionTemplate;

    public FraudAlertOutboxRecoveryService(
            MongoTemplate mongoTemplate,
            AuditMutationRecorder auditMutationRecorder,
            AlertServiceMetrics metrics,
            FraudAlertOutboxBacklogMonitor backlogMonitor,
            OutboxOperationalControls operationalControls,
            TransactionalOutboxRuntimeReadiness runtimeReadiness,
            FraudAlertPublicationEvidenceVerifier publicationEvidenceVerifier,
            WriteActionAuditOutboxService auditOutboxService,
            @Qualifier("mongoTransactionManager") PlatformTransactionManager transactionManager
    ) {
        this.mongoTemplate = mongoTemplate;
        this.auditMutationRecorder = auditMutationRecorder;
        this.metrics = metrics;
        this.backlogMonitor = backlogMonitor;
        this.operationalControls = operationalControls;
        this.runtimeReadiness = runtimeReadiness;
        this.publicationEvidenceVerifier = Objects.requireNonNull(
                publicationEvidenceVerifier,
                "publicationEvidenceVerifier is required"
        );
        this.auditOutboxService = Objects.requireNonNull(auditOutboxService, "auditOutboxService is required");
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
        return auditMutationRecorder.recordWithDurableSuccessIntent(
                AuditAction.RESOLVE_FRAUD_ALERT_OUTBOX_CONFIRMATION,
                AuditResourceType.FRAUD_ALERT_OUTBOX,
                eventId,
                null,
                actor,
                () -> resolveWithVerifiedEvidence(eventId, request, actor, idempotencyHash)
        );
    }

    private FraudAlertOutboxRecordResponse resolveWithVerifiedEvidence(
            String eventId,
            FraudAlertOutboxConfirmationResolutionRequest request,
            String actor,
            String idempotencyHash
    ) {
        requireRequestShape(request);
        String requestHash = requestHash(eventId, request, actor);
        FraudAlertOutboxResolutionRecord previousResolution = mongoTemplate.findById(
                idempotencyHash,
                FraudAlertOutboxResolutionRecord.class
        );
        if (previousResolution != null) {
            return identicalReplay(previousResolution, requestHash, actor);
        }
        FraudAlertOutboxRecord candidate = mongoTemplate.findById(eventId, FraudAlertOutboxRecord.class);
        if (candidate == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown fraud alert outbox event");
        }
        if (candidate.getStatus() != FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fraud alert outbox event is not reconcilable");
        }
        ResolutionEvidenceReference verifiedEvidence = requireResolutionEvidence(request, candidate);
        return transactionTemplate.execute(status -> resolveAuthoritativeRecord(
                eventId,
                request,
                verifiedEvidence,
                actor,
                idempotencyHash,
                requestHash,
                candidate.getRevision(),
                candidate.getUpdatedAt()
        ));
    }

    private FraudAlertOutboxRecordResponse resolveAuthoritativeRecord(
            String eventId,
            FraudAlertOutboxConfirmationResolutionRequest request,
            ResolutionEvidenceReference verifiedEvidence,
            String actor,
            String idempotencyHash,
            String requestHash,
            long verifiedRevision,
            Instant verifiedUpdatedAt
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
        if (record.getStatus() != FraudAlertOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN
                || record.getRevision() != verifiedRevision
                || !Objects.equals(record.getUpdatedAt(), verifiedUpdatedAt)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fraud alert outbox event is not reconcilable");
        }

        Instant now = Instant.now();
        Update update = new Update()
                .set("resolution", request.resolution().name())
                .set("resolutionIdempotencyHash", idempotencyHash)
                .set("resolutionRequestHash", requestHash)
                .set("resolutionReason", request.reason())
                .set("resolutionEvidenceType", verifiedEvidence.type().name())
                .set("resolutionEvidenceReference", verifiedEvidence.reference())
                .set("resolutionEvidenceVerifiedAt", verifiedEvidence.verifiedAt())
                .set("resolutionEvidenceVerifiedBy", verifiedEvidence.verifiedBy())
                .set("resolvedBy", actor)
                .set("resolvedAt", now)
                .set("resolutionPreviousAttempts", record.getAttempts())
                .set("updatedAt", now)
                .unset("leaseOwner")
                .unset("leaseToken")
                .unset("leaseExpiresAt")
                .inc("revision", 1L);
        update.set("status", FraudAlertOutboxStatus.PUBLISHED)
                .set("publishedAt", now)
                .unset("lastError");
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
        persistResolutionEvidence(
                record,
                request,
                verifiedEvidence,
                actor,
                idempotencyHash,
                requestHash,
                now,
                response
        );
        persistSuccessAuditIntent(record, actor, idempotencyHash, response);
        metrics.recordFraudAlertOutboxResolution(request.resolution().name());
        backlogMonitor.snapshotAndRecord();
        return response;
    }

    private ResolutionEvidenceReference requireResolutionEvidence(
            FraudAlertOutboxConfirmationResolutionRequest request,
            FraudAlertOutboxRecord authoritativeRecord
    ) {
        requireRequestShape(request);
        ResolutionEvidenceReference evidence = ResolutionEvidenceReference.require(
                request.evidenceReference(),
                "fraud alert outbox resolution evidence is required"
        );
        if (request.resolution() == FraudAlertOutboxConfirmationResolution.CONFIRMED_NOT_DELIVERED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "broker non-delivery cannot be proven by this deployment; confirmation remains unknown"
            );
        }
        if (evidence.type() != ResolutionEvidenceType.BROKER_OFFSET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "broker offset evidence is required");
        }
        if (evidence.verifiedAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolution evidence cannot be verified in the future");
        }
        return publicationEvidenceVerifier.verifyPublished(authoritativeRecord, evidence);
    }

    private void requireRequestShape(FraudAlertOutboxConfirmationResolutionRequest request) {
        if (request == null || request.resolution() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fraud alert outbox resolution is required");
        }
        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid fraud alert outbox resolution reason is required");
        }
        ResolutionEvidenceReference.require(
                request.evidenceReference(),
                "fraud alert outbox resolution evidence is required"
        );
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
            ResolutionEvidenceReference verifiedEvidence,
            String actor,
            String resolutionId,
            String requestHash,
            Instant resolvedAt,
            FraudAlertOutboxRecordResponse responseSnapshot
    ) {
        FraudAlertOutboxResolutionRecord resolution = new FraudAlertOutboxResolutionRecord();
        resolution.setResolutionId(resolutionId);
        resolution.setEventId(record.getEventId());
        resolution.setPreviousStatus(record.getStatus());
        resolution.setResolution(request.resolution());
        resolution.setReason(request.reason());
        resolution.setEvidenceType(verifiedEvidence.type().name());
        resolution.setEvidenceReference(verifiedEvidence.reference());
        resolution.setEvidenceVerifiedAt(verifiedEvidence.verifiedAt());
        resolution.setEvidenceVerifiedBy(verifiedEvidence.verifiedBy());
        resolution.setResolvedBy(actor);
        resolution.setResolvedAt(resolvedAt);
        resolution.setRequestHash(requestHash);
        resolution.setResponseSnapshot(responseSnapshot);
        mongoTemplate.insert(resolution);
    }

    private void persistSuccessAuditIntent(
            FraudAlertOutboxRecord record,
            String actor,
            String resolutionId,
            FraudAlertOutboxRecordResponse response
    ) {
        auditOutboxService.createPendingAudit(
                "RESOLVE_FRAUD_ALERT_OUTBOX_CONFIRMATION:" + resolutionId,
                AuditAction.RESOLVE_FRAUD_ALERT_OUTBOX_CONFIRMATION,
                AuditResourceType.FRAUD_ALERT_OUTBOX,
                record.getEventId(),
                null,
                actor,
                AuditOutcome.SUCCESS,
                new AuditEventMetadataSummary(
                        null,
                        null,
                        "alert-service",
                        "fraud-alert-outbox-resolution-v1",
                        null,
                        null,
                        "POST /api/v1/outbox/fraud-alerts/{eventId}/resolve-confirmation",
                        "status=" + response.status() + ";resolution=" + response.resolution(),
                        1
                )
        );
    }
}
