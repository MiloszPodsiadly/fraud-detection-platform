package com.frauddetection.alert.regulated.mutation.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.outbox.OutboxAlertProjectionPolicy;
import com.frauddetection.alert.outbox.OutboxConfirmationResolution;
import com.frauddetection.alert.outbox.OutboxConfirmationResolutionRequest;
import com.frauddetection.alert.outbox.OutboxPublicationConfirmationProvenance;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.mongodb.client.result.UpdateResult;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class OutboxConfirmationResolutionMutationHandler {

    private final TransactionalOutboxRecordRepository repository;
    private final MongoTemplate mongoTemplate;
    private final boolean bankModeFailClosed;
    private final boolean dualControlEnabled;
    private final Clock clock;

    @Autowired
    public OutboxConfirmationResolutionMutationHandler(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.outbox.confirmation.dual-control.enabled:false}") boolean dualControlEnabled
    ) {
        this(repository, mongoTemplate, bankModeFailClosed, dualControlEnabled, Clock.systemUTC());
    }

    public OutboxConfirmationResolutionMutationHandler(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            boolean bankModeFailClosed,
            boolean dualControlEnabled,
            Clock clock
    ) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.bankModeFailClosed = bankModeFailClosed;
        this.dualControlEnabled = dualControlEnabled;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public TransactionalOutboxRecordDocument resolve(String eventId, OutboxConfirmationResolutionRequest request, String actorId) {
        String authenticatedActor = requireAuthenticatedActor(actorId);
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
            if (request.pendingRequestId() != null && !request.pendingRequestId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pending request id is assigned by the server");
            }
            return requestResolution(record, request, authenticatedActor);
        }
        if (bankModeFailClosed && dualControlEnabled) {
            return approveResolution(record, request, authenticatedActor);
        }
        if (request.pendingRequestId() != null && !request.pendingRequestId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pending request id is only valid for dual-control approval");
        }
        return applySingleControlResolution(record, request, authenticatedActor);
    }

    private TransactionalOutboxRecordDocument requestResolution(
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        Instant transitionAt = nextUpdatedAt(record, clock.instant());
        ResolutionEvidenceReference evidence = request.evidenceReference();
        requireEvidenceVerifiedByTransition(evidence, transitionAt);
        String pendingRequestId = UUID.randomUUID().toString();
        Update update = new Update()
                .set("resolution_pending", true)
                .set("resolution_control_mode", "DUAL_CONTROL_REQUESTED")
                .set("resolution_request_id", pendingRequestId)
                .set("resolution_proposed_outcome", request.resolution().name())
                .set("resolution_requested_by", actorId)
                .set("resolution_requested_at", transitionAt)
                .set("resolution_request_reason", request.reason())
                .unset("resolution_approval_reason")
                .set("resolution_evidence_type", evidence.type().name())
                .set("resolution_evidence_reference", evidence.reference())
                .set("resolution_evidence_verified_at", evidence.verifiedAt())
                .set("resolution_evidence_verified_by", evidence.verifiedBy())
                .set("resolution_evidence_fingerprint", evidenceFingerprint(evidence))
                .unset("resolution_approved_by")
                .unset("resolution_approved_at")
                .unset("resolution_approval_evidence_type")
                .unset("resolution_approval_evidence_reference")
                .unset("resolution_approval_evidence_verified_at")
                .unset("resolution_approval_evidence_verified_by")
                .unset("resolution_approval_evidence_fingerprint")
                .set("last_error", "DUAL_CONTROL_APPROVAL_REQUIRED")
                .set("updated_at", transitionAt)
                .set("projection_reconcile_after", transitionAt)
                .inc("projection_revision", 1L);
        TransactionalOutboxRecordDocument saved = compareAndSet(record, false, update, null);
        projectSavedRecord(saved);
        return saved;
    }

    private TransactionalOutboxRecordDocument approveResolution(
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        requireCompletePendingIntent(record);
        if (request.pendingRequestId() == null || request.pendingRequestId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "approval must reference the pending request id");
        }
        if (!request.pendingRequestId().equals(record.getResolutionRequestId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "approval references a different pending request");
        }
        if (!request.resolution().name().equals(record.getResolutionProposedOutcome())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "approval cannot change the proposed resolution");
        }
        if (actorId.equals(record.getResolutionRequestedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dual-control approval requires a distinct actor");
        }
        Instant transitionAt = nextUpdatedAt(record, clock.instant());
        ResolutionEvidenceReference approvalEvidence = request.evidenceReference();
        requireEvidenceVerifiedByTransition(approvalEvidence, transitionAt);
        Update update = new Update()
                .set("resolution_control_mode", "DUAL_CONTROL_APPROVED")
                .set("resolution_approved_by", actorId)
                .set("resolution_approved_at", transitionAt)
                .set("resolution_approval_evidence_type", approvalEvidence.type().name())
                .set("resolution_approval_evidence_reference", approvalEvidence.reference())
                .set("resolution_approval_evidence_verified_at", approvalEvidence.verifiedAt())
                .set("resolution_approval_evidence_verified_by", approvalEvidence.verifiedBy())
                .set("resolution_approval_evidence_fingerprint", evidenceFingerprint(approvalEvidence));
        applyResolution(update, request, transitionAt, false);
        TransactionalOutboxRecordDocument saved = compareAndSet(
                record,
                true,
                update,
                request.pendingRequestId()
        );
        projectSavedRecord(saved);
        return saved;
    }

    private TransactionalOutboxRecordDocument applySingleControlResolution(
            TransactionalOutboxRecordDocument record,
            OutboxConfirmationResolutionRequest request,
            String actorId
    ) {
        Instant transitionAt = nextUpdatedAt(record, clock.instant());
        requireEvidenceVerifiedByTransition(request.evidenceReference(), transitionAt);
        Update update = new Update()
                .set("resolution_control_mode", "SINGLE_CONTROL_OPERATOR_ATTESTED")
                .set("resolution_approved_at", transitionAt)
                .set("resolution_approved_by", actorId);
        applyResolution(update, request, transitionAt, true);
        TransactionalOutboxRecordDocument saved = compareAndSet(record, false, update, null);
        projectSavedRecord(saved);
        return saved;
    }

    private void applyResolution(
            Update update,
            OutboxConfirmationResolutionRequest request,
            Instant transitionAt,
            boolean persistPrimaryEvidence
    ) {
        ResolutionEvidenceReference evidence = request.evidenceReference();
        TransactionalOutboxStatus status = request.resolution() == OutboxConfirmationResolution.PUBLISHED
                ? TransactionalOutboxStatus.PUBLISHED
                : TransactionalOutboxStatus.RECOVERY_REQUIRED;
        update.set("resolution_approval_reason", request.reason())
                .set("resolution_pending", false)
                .set("status", status)
                .set("updated_at", transitionAt)
                .set("projection_reconcile_after", transitionAt)
                .inc("projection_revision", 1L)
                .unset("lease_owner")
                .unset("lease_claim_token")
                .unset("lease_expires_at");
        if (persistPrimaryEvidence) {
            update.set("resolution_evidence_type", evidence.type().name())
                    .set("resolution_evidence_reference", evidence.reference())
                    .set("resolution_evidence_verified_at", evidence.verifiedAt())
                    .set("resolution_evidence_verified_by", evidence.verifiedBy())
                    .set("resolution_evidence_fingerprint", evidenceFingerprint(evidence));
        }
        if (status == TransactionalOutboxStatus.PUBLISHED) {
            OutboxPublicationConfirmationProvenance provenance = persistPrimaryEvidence
                    ? OutboxPublicationConfirmationProvenance.MANUAL_SINGLE_CONTROL_ATTESTED
                    : OutboxPublicationConfirmationProvenance.MANUAL_DUAL_CONTROL_ATTESTED;
            update.set("published_at", transitionAt)
                    .set("publication_confirmation_provenance", provenance)
                    .unset("last_error");
        } else {
            update.unset("published_at")
                    .unset("publication_confirmation_provenance")
                    .set("last_error", "MANUAL_RECOVERY_REQUIRED");
        }
    }

    private TransactionalOutboxRecordDocument compareAndSet(
            TransactionalOutboxRecordDocument record,
            boolean expectedResolutionPending,
            Update update,
            String expectedPendingRequestId
    ) {
        Criteria pending = expectedResolutionPending
                ? Criteria.where("resolution_pending").is(true)
                : new Criteria().orOperator(
                        Criteria.where("resolution_pending").is(false),
                        Criteria.where("resolution_pending").exists(false)
                );
        Criteria identity = new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN),
                pending,
                Criteria.where("projection_revision").is(record.getProjectionRevision()),
                Criteria.where("updated_at").is(record.getUpdatedAt())
        );
        Query query = expectedResolutionPending
                ? Query.query(new Criteria().andOperator(
                        identity,
                        Criteria.where("resolution_control_mode").is("DUAL_CONTROL_REQUESTED"),
                        Criteria.where("resolution_request_id").is(expectedPendingRequestId),
                        Criteria.where("resolution_proposed_outcome").is(record.getResolutionProposedOutcome()),
                        Criteria.where("resolution_requested_by").is(record.getResolutionRequestedBy()),
                        Criteria.where("resolution_requested_at").is(record.getResolutionRequestedAt()),
                        Criteria.where("resolution_request_reason").is(record.getResolutionRequestReason()),
                        Criteria.where("resolution_evidence_type").is(record.getResolutionEvidenceType()),
                        Criteria.where("resolution_evidence_reference").is(record.getResolutionEvidenceReference()),
                        Criteria.where("resolution_evidence_verified_at").is(record.getResolutionEvidenceVerifiedAt()),
                        Criteria.where("resolution_evidence_verified_by").is(record.getResolutionEvidenceVerifiedBy()),
                        Criteria.where("resolution_evidence_fingerprint").is(record.getResolutionEvidenceFingerprint())
                ))
                : Query.query(identity);
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

    private void requireEvidenceVerifiedByTransition(ResolutionEvidenceReference evidence, Instant transitionAt) {
        if (evidence.verifiedAt().isAfter(transitionAt)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "resolution evidence verification cannot occur after the resolution transition"
            );
        }
    }

    private String requireAuthenticatedActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated actor is required");
        }
        return actorId.trim();
    }

    private void requireCompletePendingIntent(TransactionalOutboxRecordDocument record) {
        if (record.getResolutionRequestId() == null || record.getResolutionRequestId().isBlank()
                || record.getResolutionProposedOutcome() == null || record.getResolutionProposedOutcome().isBlank()
                || record.getResolutionRequestedBy() == null || record.getResolutionRequestedBy().isBlank()
                || record.getResolutionRequestedAt() == null
                || record.getResolutionRequestReason() == null || record.getResolutionRequestReason().isBlank()
                || record.getResolutionEvidenceType() == null || record.getResolutionEvidenceType().isBlank()
                || record.getResolutionEvidenceReference() == null || record.getResolutionEvidenceReference().isBlank()
                || record.getResolutionEvidenceVerifiedAt() == null
                || record.getResolutionEvidenceVerifiedBy() == null || record.getResolutionEvidenceVerifiedBy().isBlank()
                || record.getResolutionEvidenceFingerprint() == null || record.getResolutionEvidenceFingerprint().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "pending resolution intent is incomplete");
        }
    }

    private String evidenceFingerprint(ResolutionEvidenceReference evidence) {
        return RegulatedMutationIntentHasher.hash(evidence);
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
            } else {
                markProjectionSynchronized(record);
            }
        } catch (DataAccessException exception) {
            markProjectionMismatch(record, "ALERT_PROJECTION_UPDATE_FAILED");
        }
    }

    private void markProjectionMismatch(TransactionalOutboxRecordDocument record, String reason) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(record.getStatus()),
                Criteria.where("projection_revision").is(record.getProjectionRevision()),
                Criteria.where("updated_at").is(record.getUpdatedAt())
        ));
        Update update = new Update()
                .set("projection_mismatch", true)
                .set("projection_mismatch_reason", reason)
                .set("updated_at", nextUpdatedAt(record, clock.instant()));
        mongoTemplate.updateFirst(query, update, TransactionalOutboxRecordDocument.class);
    }

    private void markProjectionSynchronized(TransactionalOutboxRecordDocument record) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(record.getEventId()),
                Criteria.where("status").is(record.getStatus()),
                Criteria.where("projection_revision").is(record.getProjectionRevision()),
                Criteria.where("updated_at").is(record.getUpdatedAt())
        ));
        Update update = new Update()
                .unset("projection_mismatch")
                .unset("projection_mismatch_reason")
                .unset("projection_reconcile_after")
                .unset("projection_repair_token")
                .unset("projection_repair_claimed_at");
        mongoTemplate.updateFirst(query, update, TransactionalOutboxRecordDocument.class);
    }
}
