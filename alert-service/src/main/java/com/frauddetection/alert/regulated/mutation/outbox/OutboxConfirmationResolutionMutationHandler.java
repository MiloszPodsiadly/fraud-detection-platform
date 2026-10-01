package com.frauddetection.alert.regulated.mutation.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.outbox.OutboxAlertProjectionPolicy;
import com.frauddetection.alert.outbox.OutboxConfirmationResolution;
import com.frauddetection.alert.outbox.OutboxConfirmationResolutionRequest;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
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
        return applySingleControlResolution(record, request, actorId);
    }

    private TransactionalOutboxRecordDocument requestResolution(
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        Instant now = Instant.now();
        ResolutionEvidenceReference evidence = request.evidenceReference();
        Instant updatedAt = nextUpdatedAt(record, now);
        Update update = new Update()
                .set("resolution_pending", true)
                .set("resolution_control_mode", "DUAL_CONTROL_REQUESTED")
                .set("resolution_requested_by", actorId)
                .set("resolution_requested_at", now)
                .set("resolution_request_reason", request.reason())
                .unset("resolution_approval_reason")
                .unset("resolution_reason")
                .set("resolution_evidence_type", evidence.type().name())
                .set("resolution_evidence_reference", evidence.reference())
                .set("resolution_evidence_verified_at", evidence.verifiedAt())
                .set("resolution_evidence_verified_by", evidence.verifiedBy())
                .set("last_error", "DUAL_CONTROL_APPROVAL_REQUIRED")
                .set("updated_at", updatedAt);
        TransactionalOutboxRecordDocument saved = compareAndSet(record, false, update);
        projectSavedRecord(saved);
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
        Instant now = Instant.now();
        Update update = new Update()
                .set("resolution_control_mode", "DUAL_CONTROL_APPROVED")
                .set("resolution_approved_by", actorId)
                .set("resolution_approved_at", now);
        applyResolution(update, record, request, now);
        TransactionalOutboxRecordDocument saved = compareAndSet(record, true, update);
        projectSavedRecord(saved);
        return saved;
    }

    private TransactionalOutboxRecordDocument applySingleControlResolution(
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("resolution_control_mode", "SINGLE_CONTROL_OPERATOR_ATTESTED")
                .set("resolution_approved_at", now)
                .set("resolution_approved_by", actorId);
        applyResolution(update, record, request, now);
        TransactionalOutboxRecordDocument saved = compareAndSet(record, false, update);
        projectSavedRecord(saved);
        return saved;
    }

    private void applyResolution(
            Update update,
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            Instant now
    ) {
        ResolutionEvidenceReference evidence = request.evidenceReference();
        TransactionalOutboxStatus status = request.resolution() == OutboxConfirmationResolution.PUBLISHED
                ? TransactionalOutboxStatus.PUBLISHED
                : TransactionalOutboxStatus.RECOVERY_REQUIRED;
        update.set("resolution_approval_reason", request.reason())
                .unset("resolution_reason")
                .set("resolution_evidence_type", evidence.type().name())
                .set("resolution_evidence_reference", evidence.reference())
                .set("resolution_evidence_verified_at", evidence.verifiedAt())
                .set("resolution_evidence_verified_by", evidence.verifiedBy())
                .set("resolution_pending", false)
                .set("status", status)
                .set("updated_at", nextUpdatedAt(record, now))
                .unset("lease_owner")
                .unset("lease_expires_at");
        if (status == TransactionalOutboxStatus.PUBLISHED) {
            update.set("published_at", now).unset("last_error");
        } else {
            update.unset("published_at").set("last_error", "MANUAL_RECOVERY_REQUIRED");
        }
    }

    private TransactionalOutboxRecordDocument compareAndSet(
            TransactionalOutboxRecordDocument record,
            boolean expectedResolutionPending,
            Update update
    ) {
        Criteria pending = expectedResolutionPending
                ? Criteria.where("resolution_pending").is(true)
                : new Criteria().orOperator(
                        Criteria.where("resolution_pending").is(false),
                        Criteria.where("resolution_pending").exists(false)
                );
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN),
                pending,
                Criteria.where("updated_at").is(record.getUpdatedAt())
        ));
        TransactionalOutboxRecordDocument updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                TransactionalOutboxRecordDocument.class
        );
        if (updated != null) {
            return updated;
        }
        TransactionalOutboxRecordDocument authoritative = repository.findById(record.getEventId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown outbox event"));
        if (authoritative.getStatus() != TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbox event is not confirmation-unknown");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "outbox confirmation changed concurrently");
    }

    private Instant nextUpdatedAt(TransactionalOutboxRecordDocument record, Instant now) {
        Instant previous = record.getUpdatedAt();
        return previous != null && !now.isAfter(previous) ? previous.plusMillis(1) : now;
    }

    private void projectSavedRecord(TransactionalOutboxRecordDocument record) {
        if (record.getResourceId() == null || record.getResourceId().isBlank()) {
            markProjectionMismatch(record, "ALERT_PROJECTION_RESOURCE_ID_MISSING");
            return;
        }
        OutboxAlertProjectionPolicy.Projection projection = OutboxAlertProjectionPolicy.manualResolution(record);
        try {
            UpdateResult result = mongoTemplate.updateFirst(
                    projection.target(record.getResourceId()),
                    projection.update(),
                    AlertDocument.class
            );
            if (result.getMatchedCount() == 0) {
                markProjectionMismatch(record, "ALERT_PROJECTION_NOT_FOUND");
            }
        } catch (DataAccessException exception) {
            markProjectionMismatch(record, "ALERT_PROJECTION_UPDATE_FAILED");
        }
    }

    private void markProjectionMismatch(TransactionalOutboxRecordDocument record, String reason) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(record.getStatus()),
                Criteria.where("updated_at").is(record.getUpdatedAt())
        ));
        Update update = new Update()
                .set("projection_mismatch", true)
                .set("projection_mismatch_reason", reason)
                .set("updated_at", Instant.now());
        mongoTemplate.updateFirst(query, update, TransactionalOutboxRecordDocument.class);
    }
}
