package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;
import com.frauddetection.alert.governance.audit.GovernanceEventVerificationService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class WriteActionAuditOutboxRecoveryService {
    static final int MAX_RECOVERIES = 3;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_LIST_SIZE = 100;
    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final GovernanceEventVerificationService verification;
    private final Clock clock;
    private final ObjectMapper mapper;

    @Autowired
    public WriteActionAuditOutboxRecoveryService(MongoTemplate mongo, MongoTransactionManager manager,
            GovernanceEventVerificationService verification, ObjectMapper mapper) {
        this(mongo, new TransactionTemplate(manager), verification, Clock.systemUTC(), mapper);
    }

    WriteActionAuditOutboxRecoveryService(MongoTemplate mongo, TransactionTemplate transactions,
            GovernanceEventVerificationService verification, Clock clock, ObjectMapper mapper) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.verification = verification;
        this.clock = clock;
        this.mapper = mapper;
    }

    public List<RecoveryView> list(PolicyGovernanceActor actor) {
        requireAuthority(actor);
        return mongo.find(Query.query(Criteria.where("status").is(WriteActionAuditOutboxStatus.FAILED_PERMANENT))
                        .with(Sort.by(Sort.Direction.ASC, "createdAt")).limit(MAX_LIST_SIZE),
                        WriteActionAuditOutboxRecord.class).stream().map(record -> view(actor, record)).toList();
    }

    public List<RecoveryView> history(PolicyGovernanceActor actor) {
        requireAuthority(actor);
        return mongo.find(Query.query(new Criteria()).with(Sort.by(Sort.Direction.DESC, "occurredAt"))
                        .limit(MAX_LIST_SIZE), WriteActionAuditRecoveryEvent.class).stream()
                .map(event -> {
                    boolean valid = validReceipt(event);
                    return new RecoveryView(event.operationId(), event.outboxId(), event.governanceEventKind(),
                            event.governanceEventId(), event.commitmentSha256(), event.auditRequestId(), event.action(),
                            valid ? event.result() : "RECOVERY_RECEIPT_INTEGRITY_FAILURE",
                            event.attemptCount(), event.recoveryOrdinal(), event.actorId(), event.reason(),
                            event.occurredAt(), valid ? event.receiptSha256() : null);
                }).toList();
    }

    public RecoveryView recover(PolicyGovernanceActor actor, String kindName, String eventId,
            RecoveryCommand command) {
        requireAuthority(actor);
        if (kindName == null || eventId == null || command == null
                || command.operationId() == null || !command.operationId().matches("^[A-Za-z0-9._:-]{1,128}$")
                || command.reason() == null || command.reason().isBlank()
                || command.reason().length() > MAX_REASON_LENGTH
                || !List.of("RECONCILE", "RETRY").contains(command.action())) {
            throw new IllegalArgumentException("Valid audit recovery operation, action, and reason are required");
        }
        final GovernanceEventCommitment.Kind kind;
        try { kind = GovernanceEventCommitment.Kind.valueOf(kindName); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown governance event kind"); }
        String outboxId = GovernanceEventCommitment.idFor(kind, eventId);
        final WriteActionAuditRecoveryEvent prior;
        final WriteActionAuditOutboxRecord initial;
        try {
            prior = mongo.findById(command.operationId(), WriteActionAuditRecoveryEvent.class);
            if (prior != null) return replay(prior, actor, outboxId, kind, eventId, command);
            initial = mongo.findById(outboxId, WriteActionAuditOutboxRecord.class);
        } catch (ResponseStatusException conflict) {
            throw conflict;
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Audit recovery evidence is unavailable; no state was changed", unavailable);
        }
        if (initial == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit obligation not found");
        final GovernanceEventVerificationService.Verification evidence;
        try {
            evidence = verification.verify(actor, kind, eventId);
        } catch (AccessDeniedException | IllegalArgumentException invalidRequest) {
            throw invalidRequest;
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Audit evidence is unavailable; no recovery action was taken", unavailable);
        }
        Classification classification = classify(evidence);
        String expectedAction = classification == Classification.EXACT_AUDIT_APPEND_COMMITTED ? "RECONCILE"
                : classification == Classification.NO_APPEND_PROVEN ? "RETRY" : null;
        if (expectedAction == null) {
            throw new ResponseStatusException(classification == Classification.INFRASTRUCTURE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT,
                    "Audit obligation is not safely recoverable: " + classification);
        }
        if (!expectedAction.equals(command.action())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Requested action does not match verified audit evidence: " + classification);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        try {
            WriteActionAuditRecoveryEvent result = transactions.execute(ignored -> {
                WriteActionAuditRecoveryEvent replay = mongo.findById(command.operationId(), WriteActionAuditRecoveryEvent.class);
                if (replay != null) {
                    ensureSameIntent(replay, actor, outboxId, kind, eventId, command);
                    return replay;
                }
                Criteria recoveryCount = initial.getRecoveryCount() == 0
                        ? new Criteria().orOperator(Criteria.where("recovery_count").is(0),
                                Criteria.where("recovery_count").exists(false))
                        : Criteria.where("recovery_count").is(initial.getRecoveryCount());
                Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("_id").is(outboxId),
                        Criteria.where("idempotency_key").is(outboxId),
                        Criteria.where("contract_version").is(GovernanceEventCommitment.VERSION),
                        Criteria.where("governance_event_kind").is(kind.name()),
                        Criteria.where("governance_event_id").is(eventId),
                        Criteria.where("governance_evidence_hash").is(initial.getGovernanceEvidenceHash()),
                        Criteria.where("action").is(initial.getAction()),
                        Criteria.where("resource_type").is(initial.getResourceType()),
                        Criteria.where("resource_id").is(initial.getResourceId()),
                        Criteria.where("correlation_id").is(initial.getCorrelationId()),
                        Criteria.where("actor").is(initial.getActor()),
                        Criteria.where("outcome").is(initial.getOutcome()),
                        Criteria.where("metadata_summary").is(initial.getMetadataSummary()),
                        Criteria.where("governance_scope").is(initial.getGovernanceScope()),
                        Criteria.where("governance_object_id").is(initial.getGovernanceObjectId()),
                        Criteria.where("governance_operation_id").is(initial.getGovernanceOperationId()),
                        Criteria.where("governance_proof_digest").is(initial.getGovernanceProofDigest()),
                        Criteria.where("governance_result_id").is(initial.getGovernanceResultId()),
                        Criteria.where("governance_occurred_at").is(initial.getGovernanceOccurredAt()),
                        Criteria.where("status").is(WriteActionAuditOutboxStatus.FAILED_PERMANENT),
                        Criteria.where("attempt_count").is(initial.getAttemptCount()),
                        Criteria.where("max_attempts").is(initial.getMaxAttempts()),
                        recoveryCount,
                        Criteria.where("claim_token").is(null),
                        Criteria.where("claim_expires_at").is(null)));
                boolean reconcile = "RECONCILE".equals(command.action());
                if (!reconcile && initial.getRecoveryCount() >= MAX_RECOVERIES) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Audit recovery budget exhausted");
                }
                Update update = new Update().set("last_recovery_at", now);
                if (reconcile) {
                    update.set("status", WriteActionAuditOutboxStatus.PUBLISHED)
                            .set("published_at", now).unset("next_attempt_at")
                            .unset("last_error_code")
                            .unset("last_error_message");
                } else {
                    update.set("status", WriteActionAuditOutboxStatus.PENDING)
                            .set("next_attempt_at", now).inc("recovery_count", 1)
                            .set("last_error_code", "AUTHORIZED_RECOVERY_RETRY")
                            .set("last_error_message", "Bounded audit recovery requested")
                            .unset("published_at");
                }
                WriteActionAuditOutboxRecord changed = mongo.findAndModify(query, update,
                        FindAndModifyOptions.options().returnNew(true), WriteActionAuditOutboxRecord.class);
                if (changed == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Audit recovery raced with a publisher or another operator");
                String resultStatus = reconcile ? "ACKNOWLEDGEMENT_RECONCILED" : "RETRY_QUEUED";
                int ordinal = changed.getRecoveryCount();
                Map<String, Object> receipt = Map.ofEntries(
                        Map.entry("operationId", command.operationId()), Map.entry("outboxId", outboxId),
                        Map.entry("eventKind", kind.name()), Map.entry("eventId", eventId),
                        Map.entry("commitmentSha256", initial.getGovernanceEvidenceHash()),
                        Map.entry("auditRequestId", "gov:" + initial.getGovernanceEvidenceHash()),
                        Map.entry("actorId", actor.id()), Map.entry("reason", command.reason()),
                        Map.entry("action", command.action()), Map.entry("result", resultStatus),
                        Map.entry("attemptCount", changed.getAttemptCount()), Map.entry("recoveryOrdinal", ordinal),
                        Map.entry("occurredAt", now));
                WriteActionAuditRecoveryEvent event = new WriteActionAuditRecoveryEvent(command.operationId(), outboxId,
                        kind.name(), eventId, initial.getGovernanceEvidenceHash(),
                        "gov:" + initial.getGovernanceEvidenceHash(), actor.id(), command.reason(),
                        command.action(), resultStatus, changed.getAttemptCount(), ordinal, now,
                        GovernanceCanonicalJson.sha256(mapper, receipt));
                mongo.insert(event);
                return event;
            });
            if (result == null) throw new IllegalStateException("Audit recovery transaction returned no receipt");
            return new RecoveryView(result.operationId(), result.outboxId(), result.governanceEventKind(), result.governanceEventId(),
                    result.commitmentSha256(), result.auditRequestId(), result.action(), result.result(), result.attemptCount(),
                    result.recoveryOrdinal(), result.actorId(), result.reason(), result.occurredAt(), result.receiptSha256());
        } catch (ResponseStatusException conflict) {
            throw conflict;
        } catch (RuntimeException unavailable) {
            final WriteActionAuditRecoveryEvent committed;
            try {
                committed = mongo.findById(command.operationId(), WriteActionAuditRecoveryEvent.class);
            } catch (RuntimeException unresolved) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Audit recovery outcome is unknown; retry with the same operation ID", unresolved);
            }
            if (committed != null) return replay(committed, actor, outboxId, kind, eventId, command);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Audit recovery result is unavailable; retry with the same operation ID", unavailable);
        }
    }

    private RecoveryView view(PolicyGovernanceActor actor, WriteActionAuditOutboxRecord record) {
        String kind = record.getGovernanceEventKind();
        String eventId = record.getGovernanceEventId();
        if (kind == null || eventId == null) return new RecoveryView(null, record.getOutboxId(), kind, eventId,
                record.getGovernanceEvidenceHash(), null, "UNKNOWN", Classification.IMMUTABLE_CONFLICT.name(),
                record.getAttemptCount(), record.getRecoveryCount(), null, record.getLastErrorCode(), null, null);
        try {
            var evidence = verification.verify(actor, GovernanceEventCommitment.Kind.valueOf(kind), eventId);
            Classification status = classify(evidence);
            if (status == Classification.NO_APPEND_PROVEN && record.getRecoveryCount() >= MAX_RECOVERIES)
                status = Classification.OPERATIONAL_RETRY_EXHAUSTED;
            return new RecoveryView(null, record.getOutboxId(), kind, eventId, record.getGovernanceEvidenceHash(),
                    "gov:" + record.getGovernanceEvidenceHash(),
                    status == Classification.EXACT_AUDIT_APPEND_COMMITTED ? "RECONCILE"
                            : status == Classification.NO_APPEND_PROVEN ? "RETRY" : "NONE",
                    status.name(), record.getAttemptCount(), record.getRecoveryCount(), null,
                    record.getLastErrorCode(), null, null);
        } catch (RuntimeException unavailable) {
            return new RecoveryView(null, record.getOutboxId(), kind, eventId, record.getGovernanceEvidenceHash(),
                    "gov:" + record.getGovernanceEvidenceHash(), "NONE", Classification.INFRASTRUCTURE_UNAVAILABLE.name(), record.getAttemptCount(),
                    record.getRecoveryCount(), null, record.getLastErrorCode(), null, null);
        }
    }

    private Classification classify(GovernanceEventVerificationService.Verification evidence) {
        if (evidence == null) return Classification.OUTCOME_UNKNOWN;
        if ("TAMPERED".equals(evidence.status()) || "HISTORICAL_PROOF_INVALID".equals(evidence.status()))
            return Classification.CRYPTOGRAPHIC_INTEGRITY_FAILURE;
        if (evidence.auditEventSha256() != null && evidence.chainPosition() != null)
            return Classification.EXACT_AUDIT_APPEND_COMMITTED;
        if (evidence.canonicalSha256() != null && evidence.auditEventSha256() == null
                && ("BLOCKED".equals(evidence.status()) || "RETRYABLE".equals(evidence.status())))
            return Classification.NO_APPEND_PROVEN;
        if ("MISSING_EVIDENCE".equals(evidence.status())) return Classification.IMMUTABLE_CONFLICT;
        return Classification.OUTCOME_UNKNOWN;
    }

    private RecoveryView replay(WriteActionAuditRecoveryEvent event, PolicyGovernanceActor actor, String outboxId,
            GovernanceEventCommitment.Kind kind, String eventId, RecoveryCommand command) {
        ensureSameIntent(event, actor, outboxId, kind, eventId, command);
        if (!validReceipt(event)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Recovery receipt integrity failure; incident investigation required");
        return new RecoveryView(event.operationId(), event.outboxId(), event.governanceEventKind(), event.governanceEventId(),
                event.commitmentSha256(), event.auditRequestId(), event.action(), event.result(), event.attemptCount(), event.recoveryOrdinal(),
                event.actorId(), event.reason(), event.occurredAt(), event.receiptSha256());
    }

    private void ensureSameIntent(WriteActionAuditRecoveryEvent event, PolicyGovernanceActor actor, String outboxId,
            GovernanceEventCommitment.Kind kind, String eventId, RecoveryCommand command) {
        if (!event.outboxId().equals(outboxId) || !event.governanceEventKind().equals(kind.name())
                || !event.governanceEventId().equals(eventId) || !event.actorId().equals(actor.id())
                || !event.reason().equals(command.reason()) || !event.action().equals(command.action())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recovery operation ID belongs to another intent");
        }
    }

    private void requireAuthority(PolicyGovernanceActor actor) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.AUDIT_RECOVER)
                || !actor.authorities().contains(ModelPolicyAuthority.AUDIT_READ)
                || !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production audit recovery mandate required");
        }
    }

    public record RecoveryCommand(String operationId, String reason, String action) { }
    private boolean validReceipt(WriteActionAuditRecoveryEvent event) {
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("operationId", event.operationId());
        receipt.put("outboxId", event.outboxId());
        receipt.put("eventKind", event.governanceEventKind());
        receipt.put("eventId", event.governanceEventId());
        receipt.put("commitmentSha256", event.commitmentSha256());
        receipt.put("auditRequestId", event.auditRequestId());
        receipt.put("actorId", event.actorId());
        receipt.put("reason", event.reason());
        receipt.put("action", event.action());
        receipt.put("result", event.result());
        receipt.put("attemptCount", event.attemptCount());
        receipt.put("recoveryOrdinal", event.recoveryOrdinal());
        receipt.put("occurredAt", event.occurredAt());
        return GovernanceCanonicalJson.sha256(mapper, receipt).equals(event.receiptSha256());
    }

    public record RecoveryView(String operationId, String outboxId, String eventKind, String eventId, String commitmentSha256,
            String auditRequestId,
            String action, String classification, int attemptCount, int recoveryCount, String actorId,
            String diagnostic, Instant occurredAt, String receiptSha256) { }
    enum Classification { EXACT_AUDIT_APPEND_COMMITTED, NO_APPEND_PROVEN, OPERATIONAL_RETRY_EXHAUSTED,
        IMMUTABLE_CONFLICT, CRYPTOGRAPHIC_INTEGRITY_FAILURE, OUTCOME_UNKNOWN, INFRASTRUCTURE_UNAVAILABLE }
}
