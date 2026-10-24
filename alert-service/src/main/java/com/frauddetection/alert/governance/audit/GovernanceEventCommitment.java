package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecord;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxStatus;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.Document;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Inserted in the caller's Mongo transaction alongside the immutable business event. */
public final class GovernanceEventCommitment {
    public static final String VERSION = "governance-event-commitment-v1";
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private GovernanceEventCommitment() { }

    public static String record(MongoTemplate mongo, Kind kind, String eventId, Object immutableEvent,
            String institutionalScope, String actorId, String governanceObjectId,
            String operationId, String proofDigest, String businessResultId, Instant occurredAt) {
        Objects.requireNonNull(mongo, "mongo");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(immutableEvent, "immutableEvent");
        Objects.requireNonNull(occurredAt, "occurredAt");
        String id = required(eventId, "eventId");
        String actor = required(actorId, "actorId");
        String objectId = required(governanceObjectId, "governanceObjectId");
        String scope = required(institutionalScope, "institutionalScope");
        String operation = required(operationId, "operationId");
        String result = required(businessResultId, "businessResultId");
        Object durableForm = immutableEvent;
        if (mongo.getConverter() != null) {
            Document bson = new Document();
            mongo.getConverter().write(immutableEvent, bson);
            durableForm = mongo.getConverter().read(immutableEvent.getClass(), bson);
        }
        Instant durableTime = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        String hash = hash(kind, id, durableForm, scope, actor, objectId,
                operation, proofDigest, result, durableTime);
        String outboxId = idFor(kind, id);
        var outbox = new WriteActionAuditOutboxRecord();
        outbox.setOutboxId(outboxId);
        outbox.setIdempotencyKey(outboxId);
        outbox.setContractVersion(VERSION);
        outbox.setAction(AuditAction.COMMIT_GOVERNANCE_EVENT);
        outbox.setResourceType(AuditResourceType.MODEL_GOVERNANCE_EVENT);
        outbox.setResourceId(id.length() <= 160 ? id : "sha256:" + GovernanceCanonicalJson.sha256(MAPPER, id));
        outbox.setCorrelationId(operation.length() <= 120 ? operation
                : "sha256:" + GovernanceCanonicalJson.sha256(MAPPER, operation));
        outbox.setActor(actor);
        outbox.setOutcome(AuditOutcome.SUCCESS);
        outbox.setGovernanceEventKind(kind.name());
        outbox.setGovernanceEvidenceHash(hash);
        outbox.setGovernanceEventId(id);
        outbox.setGovernanceScope(scope);
        outbox.setGovernanceObjectId(objectId);
        outbox.setGovernanceOperationId(operation);
        outbox.setGovernanceProofDigest(proofDigest);
        outbox.setGovernanceResultId(result);
        outbox.setGovernanceOccurredAt(durableTime);
        outbox.setStatus(WriteActionAuditOutboxStatus.PENDING);
        outbox.setMaxAttempts(5);
        outbox.setNextAttemptAt(durableTime);
        outbox.setCreatedAt(durableTime);
        mongo.insert(outbox);
        return hash;
    }

    public static String idFor(Kind kind, String eventId) {
        return "gov:" + GovernanceCanonicalJson.sha256(MAPPER,
                Map.of("kind", kind.name(), "eventId", eventId));
    }

    public static String hash(Kind kind, String eventId, Object immutableEvent,
            String institutionalScope, String actorId, String governanceObjectId,
            String operationId, String proofDigest, String businessResultId, Instant occurredAt) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("domain", VERSION);
        contract.put("eventKind", kind.name());
        contract.put("eventId", eventId);
        contract.put("event", immutableEvent);
        contract.put("institutionalScope", institutionalScope);
        contract.put("actorId", actorId);
        contract.put("governanceObjectId", governanceObjectId);
        contract.put("operationId", operationId);
        contract.put("proofDigest", proofDigest);
        contract.put("businessResultId", businessResultId);
        contract.put("occurredAt", occurredAt);
        return GovernanceCanonicalJson.sha256(MAPPER, contract);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("Governance audit " + name + " is invalid");
        }
        return value;
    }

    public enum Kind {
        CREDENTIAL_GRANT,
        CREDENTIAL_GRANT_REVOCATION,
        EMERGENCY_RETIREMENT,
        TRUST_CHANGE,
        POLICY_GOVERNANCE,
        POLICY_PUBLICATION,
        PUBLICATION_RECOVERY,
        PUBLICATION_RECONCILIATION
    }
}
