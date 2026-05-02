package com.frauddetection.alert.regulated.mutation.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.outbox.OutboxConfirmationResolution;
import com.frauddetection.alert.outbox.OutboxConfirmationResolutionRequest;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Component
public class OutboxConfirmationResolutionMutationHandler {

    private final TransactionalOutboxRecordRepository repository;
    private final MongoTemplate mongoTemplate;
    private final boolean bankModeFailClosed;
    private final boolean dualControlEnabled;

    public OutboxConfirmationResolutionMutationHandler(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.outbox.confirmation.dual-control.enabled:false}") boolean dualControlEnabled
    ) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.bankModeFailClosed = bankModeFailClosed;
        this.dualControlEnabled = dualControlEnabled;
    }

    public TransactionalOutboxRecordDocument resolve(String eventId, OutboxConfirmationResolutionRequest request, String actorId) {
        TransactionalOutboxRecordDocument record = repository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown outbox event"));
        if (record.getStatus() != TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbox event is not confirmation-unknown");
        }
        if (request.resolution() == OutboxConfirmationResolution.PUBLISHED) {
            ResolutionEvidenceReference.requireBrokerEvidence(request.evidenceReference());
        } else {
            ResolutionEvidenceReference.require(request.evidenceReference(), "resolution evidence is required");
        }
        if (bankModeFailClosed && !dualControlEnabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bank mode requires dual-control outbox confirmation");
        }
        if (bankModeFailClosed && dualControlEnabled && !record.isResolutionPending()) {
            return requestResolution(record, request, actorId);
        }
        if (bankModeFailClosed && dualControlEnabled) {
            return approveResolution(record, request, actorId);
        }
        record.setResolutionControlMode("SINGLE_CONTROL_OPERATOR_ATTESTED");
        return applyResolution(record, request, actorId);
    }

    private TransactionalOutboxRecordDocument requestResolution(
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        Instant now = Instant.now();
        ResolutionEvidenceReference evidence = request.evidenceReference();
        record.setResolutionPending(true);
        record.setResolutionControlMode("DUAL_CONTROL_REQUESTED");
        record.setResolutionRequestedBy(actorId);
        record.setResolutionRequestedAt(now);
        record.setResolutionReason(request.reason());
        record.setResolutionEvidenceType(evidence.type().name());
        record.setResolutionEvidenceReference(evidence.reference());
        record.setResolutionEvidenceVerifiedAt(evidence.verifiedAt());
        record.setResolutionEvidenceVerifiedBy(evidence.verifiedBy());
        record.setLastError("DUAL_CONTROL_APPROVAL_REQUIRED");
        record.setUpdatedAt(now);
        TransactionalOutboxRecordDocument saved = repository.save(record);
        markAlertResolutionPending(saved, request.reason(), actorId, evidence);
        return saved;
    }

    private TransactionalOutboxRecordDocument approveResolution(
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        if (actorId != null && actorId.equals(record.getResolutionRequestedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dual-control approval requires a distinct actor");
        }
        record.setResolutionControlMode("DUAL_CONTROL_APPROVED");
        record.setResolutionApprovedBy(actorId);
        record.setResolutionApprovedAt(Instant.now());
        record.setResolutionPending(false);
        return applyResolution(record, request, actorId);
    }

    private TransactionalOutboxRecordDocument applyResolution(
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        Instant now = Instant.now();
        ResolutionEvidenceReference evidence = request.evidenceReference();
        if (record.getResolutionControlMode() == null) {
            record.setResolutionControlMode("SINGLE_CONTROL_OPERATOR_ATTESTED");
        }
        record.setResolutionReason(request.reason());
        record.setResolutionEvidenceType(evidence.type().name());
        record.setResolutionEvidenceReference(evidence.reference());
        record.setResolutionEvidenceVerifiedAt(evidence.verifiedAt());
        record.setResolutionEvidenceVerifiedBy(evidence.verifiedBy());
        record.setResolutionPending(false);
        if (record.getResolutionApprovedAt() == null) {
            record.setResolutionApprovedAt(now);
            record.setResolutionApprovedBy(actorId);
        }
        TransactionalOutboxStatus status = request.resolution() == OutboxConfirmationResolution.PUBLISHED
                ? TransactionalOutboxStatus.PUBLISHED
                : TransactionalOutboxStatus.RECOVERY_REQUIRED;
        record.setStatus(status);
        record.setUpdatedAt(now);
        record.setLeaseOwner(null);
        record.setLeaseExpiresAt(null);
        record.setLastError(status == TransactionalOutboxStatus.RECOVERY_REQUIRED ? "MANUAL_RECOVERY_REQUIRED" : null);
        if (status == TransactionalOutboxStatus.PUBLISHED) {
            record.setPublishedAt(now);
        }
        TransactionalOutboxRecordDocument saved = repository.save(record);
        updateAlertProjection(saved, status, request.reason(), actorId, request.evidenceReference());
        return saved;
    }

    private void markAlertResolutionPending(
            TransactionalOutboxRecordDocument record,
            String reason,
            String actorId,
            ResolutionEvidenceReference evidence
    ) {
        if (record.getResourceId() == null || record.getResourceId().isBlank()) {
            return;
        }
        Update update = new Update()
                .set("decisionOutboxResolutionPending", true)
                .set("decisionOutboxResolutionRequestedAt", record.getResolutionRequestedAt())
                .set("decisionOutboxResolutionRequestedBy", actorId)
                .set("decisionOutboxResolutionApprovalReason", reason)
                .set("decisionOutboxResolutionEvidenceType", evidence.type().name())
                .set("decisionOutboxResolutionEvidenceReference", evidence.reference())
                .set("decisionOutboxResolutionEvidenceVerifiedAt", evidence.verifiedAt())
                .set("decisionOutboxResolutionEvidenceVerifiedBy", evidence.verifiedBy());
        try {
            UpdateResult result = mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(record.getResourceId())), update, AlertDocument.class);
            if (result.getMatchedCount() == 0) {
                markProjectionMismatch(record, "ALERT_PROJECTION_NOT_FOUND");
            }
        } catch (DataAccessException exception) {
            markProjectionMismatch(record, "ALERT_PROJECTION_UPDATE_FAILED");
        }
    }

    private void updateAlertProjection(
            TransactionalOutboxRecordDocument record,
            TransactionalOutboxStatus status,
            String reason,
            String actorId,
            ResolutionEvidenceReference evidence
    ) {
        if (record.getResourceId() == null || record.getResourceId().isBlank()) {
            return;
        }
        String alertStatus = status == TransactionalOutboxStatus.PUBLISHED
                ? DecisionOutboxStatus.PUBLISHED
                : DecisionOutboxStatus.FAILED_TERMINAL;
        Update update = new Update()
                .set("decisionOutboxStatus", alertStatus)
                .set("decisionOutboxResolutionApprovedAt", Instant.now())
                .set("decisionOutboxResolutionApprovedBy", actorId)
                .set("decisionOutboxResolutionApprovalReason", reason)
                .set("decisionOutboxResolutionEvidenceType", evidence.type().name())
                .set("decisionOutboxResolutionEvidenceReference", evidence.reference())
                .set("decisionOutboxResolutionEvidenceVerifiedAt", evidence.verifiedAt())
                .set("decisionOutboxResolutionEvidenceVerifiedBy", evidence.verifiedBy())
                .unset("decisionOutboxLeaseOwner")
                .unset("decisionOutboxLeaseExpiresAt")
                .unset("decisionOutboxResolutionPending");
        if (status == TransactionalOutboxStatus.PUBLISHED) {
            update.set("decisionOutboxPublishedAt", record.getPublishedAt())
                    .unset("decisionOutboxLastError")
                    .unset("decisionOutboxFailureReason");
        } else {
            update.set("decisionOutboxLastError", "MANUAL_RECOVERY_REQUIRED")
                    .set("decisionOutboxFailureReason", "MANUAL_RECOVERY_REQUIRED");
        }
        try {
            UpdateResult result = mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(record.getResourceId())), update, AlertDocument.class);
            if (result.getMatchedCount() == 0) {
                markProjectionMismatch(record, "ALERT_PROJECTION_NOT_FOUND");
            }
        } catch (DataAccessException exception) {
            markProjectionMismatch(record, "ALERT_PROJECTION_UPDATE_FAILED");
        }
    }

    private void markProjectionMismatch(TransactionalOutboxRecordDocument record, String reason) {
        record.setProjectionMismatch(true);
        record.setProjectionMismatchReason(reason);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
    }
}
