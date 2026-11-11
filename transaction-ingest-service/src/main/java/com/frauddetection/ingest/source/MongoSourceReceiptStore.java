package com.frauddetection.ingest.source;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceRawPublicationCipher;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.ingest.messaging.TransactionRawEventProducer;


import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.frauddetection.ingest.source.MongoSourceReceiptSchema.*;
import static com.frauddetection.ingest.source.SourceReceiptStore.Status.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Updates.*;

/** Owns source receipts and their encrypted, bounded RAW publication outbox; it does not read Device/Merchant history. */
final class MongoSourceReceiptStore implements SourceReceiptStore {
    static final int ATTEMPTS = 3;
    private static final int MAX_RECOVERY_EPOCHS = MongoSourceRawPublicationSchema.MAX_RECOVERY_EPOCHS;
    private static final JsonMapper RAW_JSON = JsonMapper.builder().findAndAddModules().build();
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.MAJORITY).readPreference(ReadPreference.primary()).build();
    private final MongoClient client;
    private final MongoCollection<Document> identities;
    private final MongoCollection<Document> receipts;
    private final MongoCollection<Document> capacity;
    private final Clock clock;
    private final Limits limits;
    private final SourceSignatureVerifier verifier;
    private final String limitsCommitment;
    private final MongoSourceLineage lineage;
    private final SourceRawPublicationCipher rawCipher;
    private final SourceRawPublicationProperties rawProperties;
    private final MongoCollection<Document> rawPublications;

    MongoSourceReceiptStore(MongoClient client, String databaseName, Clock clock, Limits limits, SourceSignatureVerifier verifier) {
        this(client, databaseName, clock, limits, verifier, null, null,
                new SourceRawPublicationProperties(false, null, null, null, null, 0, 0));
    }

    MongoSourceReceiptStore(MongoClient client, String databaseName, Clock clock, Limits limits, SourceSignatureVerifier verifier,
                            MongoSourceLineage lineage, SourceRawPublicationCipher rawCipher,
                            SourceRawPublicationProperties rawProperties) {
        this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        this.verifier = Objects.requireNonNull(verifier);
        this.lineage = lineage;
        this.rawCipher = rawCipher;
        this.rawProperties = Objects.requireNonNull(rawProperties);
        if (rawProperties.enabled() && (lineage == null || rawCipher == null)) { throw invalid("RAW_PUBLICATION_DEPENDENCIES_REQUIRED"); }
        if (databaseName == null || !databaseName.matches("[a-z][a-z0-9_]{0,63}")) { throw invalid("CONFIGURATION_INVALID"); }
        var database = client.getDatabase(databaseName).withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY).withReadPreference(ReadPreference.primary());
        Long timeout = database.getTimeout(TimeUnit.MILLISECONDS);
        if (timeout == null || timeout <= 0 || timeout > 5_000) { throw invalid("OPERATION_TIMEOUT_REQUIRED"); }
        Document hello = database.runCommand(new Document("hello", 1));
        if (hello.getString("setName") == null || !Boolean.TRUE.equals(hello.get("isWritablePrimary"))) {
            throw invalid("REPLICA_SET_REQUIRED");
        }
        boolean fresh = initialize(database);
        identities = database.getCollection(IDENTITIES);
        receipts = database.getCollection(RECEIPTS);
        capacity = database.getCollection(LIMITS);
        if (rawProperties.enabled()) { MongoSourceRawPublicationSchema.initialize(database); }
        rawPublications = rawProperties.enabled() ? database.getCollection(MongoSourceRawPublicationSchema.COLLECTION) : null;
        limitsCommitment = SourceReceiptCommitment.limits(limits);
        if (fresh) {
            capacity.insertOne(new Document("_id", "capacity").append("schemaVersion", 1)
                    .append("policyCommitment", limitsCommitment).append("count", 0));
        }
        Document counter = capacity.find(eq("_id", "capacity")).first();
        if (!validCounter(counter)) { throw invalid("CAPACITY_POLICY_INCOMPATIBLE"); }
    }

    @Override public Result admit(SourceSignatureVerifier.Verified evidence) {
        return authorized(evidence, session -> admit(session, evidence));
    }

    private Result admit(ClientSession session, SourceSignatureVerifier.Verified evidence) {
        Instant now = now();
        SourceScope scope = evidence.scope();
        if (evidence.verifiedAt().truncatedTo(ChronoUnit.MILLIS).isAfter(now)
                || scope.eventTime().isAfter(now.plusSeconds(300))) { return result(UNVERIFIED); }
        String id = SourceReceiptCommitment.identity(scope);
        String binding = SourceReceiptCommitment.binding(scope, evidence.keyCommitment());
        Document existing = identities.find(session, eq("_id", id)).first();
        if (existing != null) {
            if (expired(existing, now) || "TOMBSTONED".equals(existing.getString("state"))) {
                tombstone(session, existing); return result(TOMBSTONED);
            }
            if ("CONFLICT".equals(existing.getString("state"))) { return result(CONFLICT); }
            if (!binding.equals(existing.getString("bindingCommitment"))) {
                var updated = identities.updateOne(session, and(eq("_id", id), eq("state", "ADMITTED")), set("state", "CONFLICT"));
                if (!updated.wasAcknowledged() || updated.getMatchedCount() != 1 || updated.getModifiedCount() != 1) {
                    throw invalid("FENCE_UNRESOLVED");
                }
                return result(CONFLICT);
            }
            return existing(session, evidence, existing, EXISTING);
        }
        if (!scope.eventTime().isAfter(now.minus(limits.replayHorizon()))) { return result(REPLAY_EXPIRED); }
        // An orphaned receipt is not permission to reconstruct or overwrite an identity fence.
        if (receipts.find(session, eq("_id", id)).first() != null) { return result(UNRESOLVED); }
        Document counter = capacity.find(session, eq("_id", "capacity")).first();
        if (!validCounter(counter)) { return result(UNRESOLVED); }
        if (counter.getInteger("count") >= limits.maxReceipts()) { return result(CAPACITY_EXCEEDED); }
        int capacitySlot = counter.getInteger("count") + 1;
        if (identities.find(session, eq("capacitySlot", capacitySlot)).hint(CAPACITY_INDEX).first() != null) { return result(UNRESOLVED); }
        var reserved = capacity.updateOne(session, and(eq("_id", "capacity"), eq("policyCommitment", limitsCommitment),
                lt("count", limits.maxReceipts())), inc("count", 1));
        if (!reserved.wasAcknowledged() || reserved.getModifiedCount() != 1) { throw invalid("CAPACITY_UNRESOLVED"); }
        Instant expiresAt = now.plus(limits.receiptRetention());
        Document identity = new Document("_id", id).append("schemaVersion", 1).append("issuer", scope.issuer())
                .append("institution", scope.institution()).append("source", scope.source()).append("sourceEpoch", scope.sourceEpoch())
                .append("sourceEventId", scope.sourceEventId()).append("bindingCommitment", binding).append("state", "ADMITTED")
                .append("admittedAt", Date.from(now)).append("expiresAt", Date.from(expiresAt)).append("capacitySlot", capacitySlot);
        Document receipt = new Document("_id", id).append("schemaVersion", 1).append("signingKeyVersion", scope.signingKeyVersion())
                .append("eventTime", scope.eventTime().toString()).append("purpose", scope.purpose()).append("audience", scope.audience())
                .append("payloadCommitment", scope.payloadCommitment()).append("keyCommitment", evidence.keyCommitment())
                .append("policyVersion", evidence.policyVersion()).append("verifiedAt", evidence.verifiedAt().toString())
                .append("bindingCommitment", binding).append("admittedAt", Date.from(now)).append("expiresAt", Date.from(expiresAt));
        if (!identities.insertOne(session, identity).wasAcknowledged() || !receipts.insertOne(session, receipt).wasAcknowledged()) {
            throw invalid("INSERT_UNRESOLVED");
        }
        return new Result(ADMITTED, new Receipt(id, scope, evidence.keyCommitment(), evidence.policyVersion(),
                evidence.verifiedAt(), now, expiresAt));
    }

    @Override public RawResult acceptRaw(SourceSignatureVerifier.Verified evidence, TransactionRawEvent candidate) {
        if (!rawProperties.enabled() || lineage == null || rawCipher == null || rawPublications == null) {
            return rawResult(UNRESOLVED);
        }
        if (candidate == null) { return rawResult(UNVERIFIED); }
        return rawTransaction(evidence, session -> {
            Result admission = admit(session, evidence);
            if (admission.status() != ADMITTED && admission.status() != EXISTING) { return rawResult(admission.status()); }
            Receipt receipt = admission.receipt();
            Document existing = rawPublications.find(session, eq("_id", receipt.id())).first();
            if (existing != null) {
                TransactionRawEvent event = restoreRaw(existing, receipt);
                return event == null ? rawResult(UNRESOLVED) : new RawResult(admission.status(), receipt, event);
            }
            // An old/partial receipt is not authority to mint a new receiver event identity.
            if (admission.status() == EXISTING) { return rawResult(UNRESOLVED); }
            SourceLineage binding = lineage.bindRawWithinTransaction(session, evidence, receipt.id(), candidate);
            if (binding.state() != SourceLineage.State.VERIFIED) {
                if (binding.state() == SourceLineage.State.ORDER_PENDING) {
                    throw new RawTransactionAbort(rawResult(ORDER_PENDING));
                }
                if (binding.state() == SourceLineage.State.CONFLICT) {
                    var fenced = identities.updateOne(session, and(eq("_id", receipt.id()), eq("state", "ADMITTED")), set("state", "CONFLICT"));
                    if (!fenced.wasAcknowledged() || fenced.getMatchedCount() != 1) { throw invalid("FENCE_UNRESOLVED"); }
                    return rawResult(CONFLICT);
                }
                throw new RawTransactionAbort(rawResult(status(binding.state())));
            }
            String commitment = SourceEventCommitment.raw(candidate);
            Instant now = now();
            Document row = new Document("_id", receipt.id()).append("schemaVersion", 1).append("receiptId", receipt.id())
                    .append("rawCommitment", commitment).append("eventId", candidate.eventId())
                    .append("eventCreatedAt", candidate.createdAt().toString()).append("createdAt", Date.from(now))
                    .append("expiresAt", Date.from(receipt.expiresAt())).append("state", "PENDING").append("attempts", 0)
                    .append("nextAttemptAt", Date.from(now)).append("recoveryEpoch", 0);
            rawCipher.encrypt(row, RAW_JSON.writeValueAsString(candidate).getBytes(StandardCharsets.UTF_8));
            if (!rawPublications.insertOne(session, row).wasAcknowledged()) { throw invalid("RAW_PUBLICATION_INSERT_UNRESOLVED"); }
            return new RawResult(ADMITTED, receipt, candidate);
        });
    }

    @Override public int recoverRawPublications(TransactionRawEventProducer publisher, int limit) {
        if (!rawProperties.enabled() || publisher == null || limit < 1 || limit > 50) { return 0; }
        quarantineExhausted();
        int handled = 0;
        for (; handled < limit; ) {
            Claim claim = claimRawPublication();
            if (claim == null) { break; }
            handled++;
            if (claim.event() == null) {
                quarantine(claim, "PAYLOAD_UNAVAILABLE");
                continue;
            }
            SourceLineage authorization;
            try { authorization = lineage.raw(claim.event()); }
            catch (RuntimeException unavailable) {
                retry(claim);
                continue;
            }
            if (authorization.state() == SourceLineage.State.UNAVAILABLE) {
                retry(claim);
                continue;
            }
            if (authorization.state() != SourceLineage.State.VERIFIED || !claim.receiptId().equals(authorization.receiptId())) {
                quarantine(claim, "AUTHORITY_DENIED");
                continue;
            }
            try {
                publisher.publish(claim.event(), SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            } catch (RuntimeException uncertainBrokerAck) {
                retry(claim);
                continue;
            }
            markPublished(claim);
        }
        return handled;
    }

    @Override public RedriveResult redriveQuarantined(String receiptId, RedriveReason reason) {
        if (!rawProperties.enabled() || rawPublications == null || receiptId == null || !receiptId.matches("^[0-9a-f]{64}$")
                || reason == null) { return redriveResult(RedriveStatus.INVALID, 0); }
        Date redriveAt = Date.from(now());
        Document row;
        try { row = rawPublications.find(eq("_id", receiptId)).first(); }
        catch (RuntimeException unavailable) { return redriveResult(RedriveStatus.UNAVAILABLE, 0); }
        if (row == null || !"QUARANTINED".equals(row.getString("state"))) {
            return redriveResult(RedriveStatus.UNAVAILABLE, row == null ? 0 : recoveryEpoch(row));
        }
        if (!"PUBLISH_FAILED".equals(row.getString("failureCode"))
                || !(row.get("expiresAt") instanceof Date expiry) || !expiry.toInstant().isAfter(now())) {
            return redriveResult(RedriveStatus.INVALID, recoveryEpoch(row));
        }
        int epoch = recoveryEpoch(row);
        if (epoch < 0) { return redriveResult(RedriveStatus.INVALID, 0); }
        if (epoch >= MAX_RECOVERY_EPOCHS) { return redriveResult(RedriveStatus.EXHAUSTED, epoch); }

        TransactionRawEvent event = restoreRawPayload(row, Set.of("QUARANTINED"));
        if (event == null) {
            markQuarantineFailure(row, "PAYLOAD_UNAVAILABLE");
            return redriveResult(RedriveStatus.AUTHORITY_DENIED, epoch);
        }
        SourceLineage authorization;
        try { authorization = lineage.raw(event); }
        catch (RuntimeException unavailable) { return redriveResult(RedriveStatus.UNAVAILABLE, epoch); }
        if (authorization == null || authorization.state() == SourceLineage.State.UNAVAILABLE) {
            return redriveResult(RedriveStatus.UNAVAILABLE, epoch);
        }
        if (authorization.state() != SourceLineage.State.VERIFIED || !receiptId.equals(authorization.receiptId())) {
            markQuarantineFailure(row, "AUTHORITY_DENIED");
            return redriveResult(RedriveStatus.AUTHORITY_DENIED, epoch);
        }
        Document receipt = receipts.find(eq("_id", receiptId)).first();
        if (receipt == null || !Objects.equals(receipt.get("expiresAt"), row.get("expiresAt"))) {
            markQuarantineFailure(row, "AUTHORITY_DENIED");
            return redriveResult(RedriveStatus.AUTHORITY_DENIED, epoch);
        }

        Date nowDate = Date.from(now());
        int nextEpoch = epoch + 1;
        var epochFilter = epoch == 0 ? or(eq("recoveryEpoch", 0), exists("recoveryEpoch", false)) : eq("recoveryEpoch", epoch);
        try {
            var update = rawPublications.updateOne(and(eq("_id", receiptId), eq("state", "QUARANTINED"),
                            eq("failureCode", "PUBLISH_FAILED"), eq("rawCommitment", row.getString("rawCommitment")),
                            eq("eventId", row.getString("eventId")), eq("eventCreatedAt", row.getString("eventCreatedAt")),
                            eq("keyId", row.getString("keyId")), eq("nonce", row.get("nonce")), eq("ciphertext", row.get("ciphertext")),
                            epochFilter, gt("expiresAt", nowDate), or(exists("leaseUntil", false), lte("leaseUntil", nowDate))),
                    combine(set("state", "PENDING"), set("attempts", 0), set("recoveryEpoch", nextEpoch),
                            set("nextAttemptAt", nowDate), set("lastRedriveAt", redriveAt), set("redriveReason", reason.name()),
                            unset("failureCode"), unset("leaseToken"), unset("leaseUntil")));
            if (update.wasAcknowledged() && update.getMatchedCount() == 1 && update.getModifiedCount() == 1) {
                return redriveResult(RedriveStatus.REQUEUED, nextEpoch);
            }
        } catch (RuntimeException unresolvedWrite) {
            // Read-back below reconciles a lost acknowledgement without creating a new RAW identity or payload.
        }
        return reconcileRedrive(row, nextEpoch, redriveAt, reason);
    }

    @Override public RecoveryHealth recoveryHealth() {
        if (!rawProperties.enabled() || rawPublications == null) { return new RecoveryHealth(0, 0, 0, 0, 0, 0, 0); }
        Date nowDate = Date.from(now());
        var active = and(gt("expiresAt", nowDate));
        long pending = rawPublications.countDocuments(and(active, eq("state", "PENDING")));
        long leased = rawPublications.countDocuments(and(active, eq("state", "PENDING"), gt("leaseUntil", nowDate)));
        long publishFailed = rawPublications.countDocuments(and(active, eq("state", "QUARANTINED"), eq("failureCode", "PUBLISH_FAILED")));
        long authorityDenied = rawPublications.countDocuments(and(active, eq("state", "QUARANTINED"), eq("failureCode", "AUTHORITY_DENIED")));
        long payloadUnavailable = rawPublications.countDocuments(and(active, eq("state", "QUARANTINED"), eq("failureCode", "PAYLOAD_UNAVAILABLE")));
        long epochExhausted = rawPublications.countDocuments(and(active, eq("state", "QUARANTINED"), eq("failureCode", "PUBLISH_FAILED"),
                gte("recoveryEpoch", MAX_RECOVERY_EPOCHS)));
        var recoverable = and(active, or(eq("state", "PENDING"), and(eq("state", "QUARANTINED"),
                eq("failureCode", "PUBLISH_FAILED"), or(lt("recoveryEpoch", MAX_RECOVERY_EPOCHS), exists("recoveryEpoch", false)))));
        Document oldest = rawPublications.find(recoverable).sort(ascending("createdAt"))
                .projection(new Document("createdAt", 1)).first();
        long age = oldest == null ? 0 : Math.max(0, java.time.Duration.between(oldest.getDate("createdAt").toInstant(), now()).toSeconds());
        return new RecoveryHealth(pending, leased, publishFailed, authorityDenied, payloadUnavailable, epochExhausted, age);
    }

    private RawResult rawTransaction(SourceSignatureVerifier.Verified evidence, Function<ClientSession, RawResult> work) {
        if (evidence == null) { return rawResult(UNVERIFIED); }
        if (verifier.currentDenial(evidence) != null) { return rawResult(REVOKED); }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TRANSACTION);
                try {
                    RawResult outcome = work.apply(session);
                    if (verifier.currentDenial(evidence) != null) {
                        abort(session);
                        return rawResult(REVOKED);
                    }
                    commit(session);
                    return outcome;
                } catch (MongoException failure) {
                    if (failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) { return rawResult(UNRESOLVED); }
                    abort(session);
                    if ((failure.getCode() == 11000 || failure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL))
                            && attempt + 1 < ATTEMPTS) { continue; }
                    return rawResult(UNRESOLVED);
                } catch (RawTransactionAbort aborted) {
                    abort(session);
                    return aborted.result;
                } catch (RuntimeException corruptOrUnavailable) {
                    abort(session);
                    return rawResult(UNRESOLVED);
                }
            } catch (RuntimeException unavailable) { return rawResult(UNRESOLVED); }
        }
        return rawResult(UNRESOLVED);
    }

    private TransactionRawEvent restoreRaw(Document row, Receipt receipt) {
        try {
            if (row.getInteger("schemaVersion", 0) != 1 || !receipt.id().equals(row.getString("receiptId"))
                    || !Objects.equals(receipt.expiresAt(), row.getDate("expiresAt").toInstant())) { return null; }
            return restoreRawPayload(row);
        } catch (RuntimeException unavailable) { return null; }
    }

    private TransactionRawEvent restoreRawPayload(Document row) {
        return restoreRawPayload(row, Set.of("PENDING", "PUBLISHED"));
    }

    private TransactionRawEvent restoreRawPayload(Document row, Set<String> allowedStates) {
        try {
            if (row.getInteger("schemaVersion", 0) != 1 || !Objects.equals(row.getString("_id"), row.getString("receiptId"))
                    || !allowedStates.contains(row.getString("state"))) { return null; }
            byte[] bytes = rawCipher.decrypt(row);
            if (bytes.length == 0 || bytes.length > SourceRawPublicationCipher.MAX_BYTES) { return null; }
            TransactionRawEvent event = RAW_JSON.readValue(new String(bytes, StandardCharsets.UTF_8), TransactionRawEvent.class);
            if (event == null || !Objects.equals(row.getString("eventId"), event.eventId())
                    || !Objects.equals(row.getString("eventCreatedAt"), event.createdAt().toString())
                    || !Objects.equals(row.getString("rawCommitment"), SourceEventCommitment.raw(event))) { return null; }
            return event;
        } catch (RuntimeException unavailable) { return null; }
    }

    private void markQuarantineFailure(Document original, String safeReason) {
        if (!Set.of("AUTHORITY_DENIED", "PAYLOAD_UNAVAILABLE").contains(safeReason)) { return; }
        try {
            rawPublications.updateOne(and(eq("_id", original.getString("_id")), eq("state", "QUARANTINED"),
                            eq("failureCode", "PUBLISH_FAILED"), eq("rawCommitment", original.getString("rawCommitment")),
                            eq("ciphertext", original.get("ciphertext"))), set("failureCode", safeReason));
        } catch (RuntimeException unavailable) {
            // The event remains quarantined and cannot be claimed by automatic publication recovery.
        }
    }

    private RedriveResult reconcileRedrive(Document original, int expectedEpoch, Date redriveAt, RedriveReason reason) {
        try {
            Document current = rawPublications.find(eq("_id", original.getString("_id"))).first();
            if (current != null && Set.of("PENDING", "PUBLISHED").contains(current.getString("state"))
                    && recoveryEpoch(current) == expectedEpoch && reason.name().equals(current.getString("redriveReason"))
                    && Objects.equals(redriveAt, current.getDate("lastRedriveAt"))
                    && Objects.equals(original.getString("receiptId"), current.getString("receiptId"))
                    && Objects.equals(original.getString("rawCommitment"), current.getString("rawCommitment"))
                    && Objects.equals(original.getString("eventId"), current.getString("eventId"))
                    && Objects.equals(original.getString("eventCreatedAt"), current.getString("eventCreatedAt"))
                    && Objects.equals(original.get("expiresAt"), current.get("expiresAt"))
                    && Objects.equals(original.get("keyId"), current.get("keyId"))
                    && Objects.equals(original.get("nonce"), current.get("nonce"))
                    && Objects.equals(original.get("ciphertext"), current.get("ciphertext"))) {
                return redriveResult(RedriveStatus.REQUEUED, expectedEpoch);
            }
        } catch (RuntimeException unavailable) {
            // Preserve uncertainty rather than reporting a successful redrive without an exact read-back.
        }
        return redriveResult(RedriveStatus.UNAVAILABLE, recoveryEpoch(original));
    }

    private static int recoveryEpoch(Document row) {
        if (!row.containsKey("recoveryEpoch")) { return 0; }
        Object value = row.get("recoveryEpoch");
        if (!(value instanceof Integer epoch) || epoch < 0 || epoch > MAX_RECOVERY_EPOCHS) { return -1; }
        return epoch;
    }

    private static RedriveResult redriveResult(RedriveStatus status, int epoch) {
        return new RedriveResult(status, Math.max(0, Math.min(MAX_RECOVERY_EPOCHS, epoch)));
    }

    private Claim claimRawPublication() {
        Instant now = now();
        String token = UUID.randomUUID().toString();
        Document row;
        try {
            row = rawPublications.findOneAndUpdate(and(eq("state", "PENDING"), lt("attempts", rawProperties.maxAttempts()),
                            lte("nextAttemptAt", Date.from(now)), gt("expiresAt", Date.from(now)),
                            or(exists("leaseUntil", false), lte("leaseUntil", Date.from(now)))),
                    combine(inc("attempts", 1), set("leaseToken", token),
                            set("leaseUntil", Date.from(now.plus(rawProperties.claimLease())))),
                    new FindOneAndUpdateOptions().sort(ascending("nextAttemptAt")).returnDocument(ReturnDocument.AFTER));
        } catch (RuntimeException unavailable) { return null; }
        if (row == null) { return null; }
        Claim claim = new Claim(row.getString("_id"), row.getString("leaseToken"), row.getInteger("attempts", 0), null);
        TransactionRawEvent event = restoreRawPayload(row);
        return new Claim(claim.receiptId(), claim.leaseToken(), claim.attempts(), event);
    }

    private void markPublished(Claim claim) {
        try {
            rawPublications.updateOne(and(eq("_id", claim.receiptId()), eq("state", "PENDING"), eq("leaseToken", claim.leaseToken())),
                    combine(set("state", "PUBLISHED"), set("publishedAt", Date.from(now())),
                            unset("leaseToken"), unset("leaseUntil"), unset("failureCode")));
        } catch (RuntimeException uncertainCommit) {
            // The lease expires and the exact same event is replayed if this ACK state was not committed.
        }
    }

    private void quarantineExhausted() {
        Instant now = now();
        try {
            rawPublications.updateMany(and(eq("state", "PENDING"), gte("attempts", rawProperties.maxAttempts()),
                            gt("expiresAt", Date.from(now)), or(exists("leaseUntil", false), lte("leaseUntil", Date.from(now)))),
                    combine(set("state", "QUARANTINED"), set("failureCode", "PUBLISH_FAILED"), unset("leaseToken"), unset("leaseUntil")));
        } catch (RuntimeException unavailable) { /* Bounded retry state remains durable and visible for the next pass. */ }
    }

    private void retry(Claim claim) {
        int delaySeconds = Math.min(300, 1 << Math.min(8, claim.attempts()));
        String next = claim.attempts() >= rawProperties.maxAttempts() ? "QUARANTINED" : "PENDING";
        try {
            rawPublications.updateOne(and(eq("_id", claim.receiptId()), eq("state", "PENDING"), eq("leaseToken", claim.leaseToken())),
                    combine(set("state", next), set("nextAttemptAt", Date.from(now().plusSeconds(delaySeconds))),
                            set("failureCode", "PUBLISH_FAILED"), unset("leaseToken"), unset("leaseUntil")));
        } catch (RuntimeException unavailable) { /* Lease expiry permits bounded recovery. */ }
    }

    private void quarantine(Claim claim, String reason) {
        try {
            rawPublications.updateOne(and(eq("_id", claim.receiptId()), eq("state", "PENDING"), eq("leaseToken", claim.leaseToken())),
                    combine(set("state", "QUARANTINED"), set("failureCode", reason), unset("leaseToken"), unset("leaseUntil")));
        } catch (RuntimeException unavailable) { /* The lease remains bounded and no event is published. */ }
    }

    private static Status status(SourceLineage.State state) {
        return switch (state) {
            case VERIFIED -> ADMITTED;
            case UNVERIFIED -> UNVERIFIED;
            case CONFLICT -> CONFLICT;
            case REVOKED -> REVOKED;
            case UNAVAILABLE -> UNRESOLVED;
            case ORDER_PENDING -> ORDER_PENDING;
        };
    }

    private static RawResult rawResult(Status status) { return new RawResult(status, null, null); }
    private record Claim(String receiptId, String leaseToken, int attempts, TransactionRawEvent event) { }
    private static final class RawTransactionAbort extends RuntimeException {
        private final RawResult result;
        private RawTransactionAbort(RawResult result) { this.result = result; }
    }

    @Override public ReadOnly readOnly() { return this::lookup; }

    private Result lookup(SourceSignatureVerifier.Verified evidence, String receiptId) {
        if (evidence == null || !SourceReceiptCommitment.identity(evidence.scope()).equals(receiptId)) { return result(UNVERIFIED); }
        return authorized(evidence, session -> {
            Document identity = identities.find(session, eq("_id", receiptId)).first();
            if (identity == null) { return result(UNVERIFIED); }
            if (expired(identity, now()) || "TOMBSTONED".equals(identity.getString("state"))) { return result(TOMBSTONED); }
            if ("CONFLICT".equals(identity.getString("state"))) { return result(CONFLICT); }
            return existing(session, evidence, identity, ADMITTED);
        });
    }

    private Result existing(ClientSession session, SourceSignatureVerifier.Verified evidence, Document identity, Status status) {
        if (!"ADMITTED".equals(identity.getString("state")) || identity.getInteger("schemaVersion", 0) != 1) {
            return result(UNRESOLVED);
        }
        Document receipt = receipts.find(session, eq("_id", identity.getString("_id"))).first();
        if (receipt == null || receipt.getInteger("schemaVersion", 0) != 1) { return result(UNRESOLVED); }
        SourceScope scope = new SourceScope(identity.getString("issuer"), identity.getString("institution"), identity.getString("source"),
                identity.getString("sourceEpoch"), receipt.getString("signingKeyVersion"), identity.getString("sourceEventId"),
                canonicalTime(receipt.getString("eventTime")), receipt.getString("purpose"), receipt.getString("audience"),
                receipt.getString("payloadCommitment"));
        String binding = SourceReceiptCommitment.binding(scope, receipt.getString("keyCommitment"));
        if (!SourceReceiptCommitment.identity(scope).equals(identity.getString("_id"))
                || !binding.equals(identity.getString("bindingCommitment")) || !binding.equals(receipt.getString("bindingCommitment"))
                || !scope.equals(evidence.scope()) || !evidence.keyCommitment().equals(receipt.getString("keyCommitment"))
                || !Objects.equals(identity.get("admittedAt"), receipt.get("admittedAt"))
                || !Objects.equals(identity.get("expiresAt"), receipt.get("expiresAt"))) { return result(UNRESOLVED); }
        if (!evidence.policyVersion().equals(receipt.getString("policyVersion"))) { return result(REVOKED); }
        Instant verifiedAt = canonicalTime(receipt.getString("verifiedAt"));
        if (verifier.receiptPolicyDenial(scope, receipt.getString("keyCommitment"), receipt.getString("policyVersion"), verifiedAt) != null) {
            return result(REVOKED);
        }
        Instant admittedAt = receipt.getDate("admittedAt").toInstant();
        Instant expiresAt = receipt.getDate("expiresAt").toInstant();
        if (verifiedAt.truncatedTo(ChronoUnit.MILLIS).isAfter(admittedAt)
                || !expiresAt.equals(admittedAt.plus(limits.receiptRetention())) || admittedAt.isAfter(now())) {
            return result(UNRESOLVED);
        }
        return new Result(status, new Receipt(identity.getString("_id"), scope, receipt.getString("keyCommitment"),
                receipt.getString("policyVersion"), verifiedAt, admittedAt, expiresAt));
    }

    @Override public int compactExpired(int limit) {
        if (limit < 1 || limit > 100) { throw invalid("COMPACTION_LIMIT_INVALID"); }
        List<Document> candidates;
        try {
            candidates = identities.find(and(in("state", List.of("ADMITTED", "CONFLICT")), lte("expiresAt", Date.from(now()))))
                    .hint(EXPIRY_INDEX).limit(limit).into(new java.util.ArrayList<>());
        } catch (RuntimeException unavailable) { throw invalid("COMPACTION_UNRESOLVED"); }
        int compacted = 0;
        for (Document candidate : candidates) {
            Result outcome = transaction(session -> {
                Document current = identities.find(session, eq("_id", candidate.getString("_id"))).first();
                if (current == null || !expired(current, now())) { return result(UNVERIFIED); }
                tombstone(session, current); return result(TOMBSTONED);
            }, null);
            if (outcome.status() == UNRESOLVED) { throw invalid("COMPACTION_UNRESOLVED"); }
            if (outcome.status() == TOMBSTONED) { compacted++; }
        }
        return compacted;
    }

    private void tombstone(ClientSession session, Document identity) {
        var update = identities.updateOne(session, eq("_id", identity.getString("_id")), set("state", "TOMBSTONED"));
        if (!update.wasAcknowledged() || update.getMatchedCount() != 1) { throw invalid("FENCE_UNRESOLVED"); }
        if (!receipts.deleteOne(session, eq("_id", identity.getString("_id"))).wasAcknowledged()) { throw invalid("COMPACTION_UNRESOLVED"); }
        if (rawPublications != null && !rawPublications.deleteOne(session, eq("_id", identity.getString("_id"))).wasAcknowledged()) {
            throw invalid("RAW_PUBLICATION_COMPACTION_UNRESOLVED");
        }
        if (lineage != null && !lineage.deleteEnrichedPublicationsWithinTransaction(session, identity.getString("_id"))) {
            throw invalid("ENRICHED_PUBLICATION_COMPACTION_UNRESOLVED");
        }
    }

    private Result authorized(SourceSignatureVerifier.Verified evidence, Function<ClientSession, Result> work) {
        if (evidence == null) { return result(UNVERIFIED); }
        if (verifier.currentDenial(evidence) != null) { return result(REVOKED); }
        return transaction(work, evidence);
    }

    private Result transaction(Function<ClientSession, Result> work, SourceSignatureVerifier.Verified authorization) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TRANSACTION);
                try {
                    Result result = work.apply(session);
                    if (authorization != null && verifier.currentDenial(authorization) != null) {
                        abort(session); return result(REVOKED);
                    }
                    commit(session);
                    return result;
                } catch (MongoException failure) {
                    // Never rerun a body after an unknown commit: only commit retries or an explicit later lookup.
                    if (failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) { return result(UNRESOLVED); }
                    abort(session);
                    if ((failure.getCode() == 11000 || failure.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL))
                            && attempt + 1 < ATTEMPTS) { continue; }
                    return result(UNRESOLVED);
                } catch (RuntimeException corruptOrUnavailable) {
                    abort(session); return result(UNRESOLVED);
                }
            } catch (RuntimeException unavailable) { return result(UNRESOLVED); }
        }
        return result(UNRESOLVED);
    }

    private void commit(ClientSession session) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try { session.commitTransaction(); return; }
            catch (MongoException failure) {
                if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) || attempt + 1 == ATTEMPTS) { throw failure; }
            }
        }
    }
    private void abort(ClientSession session) {
        try { if (session.hasActiveTransaction()) { session.abortTransaction(); } }
        catch (MongoException ignored) { /* No failure path reports a successful admission. */ }
    }
    private boolean validCounter(Document counter) {
        return counter != null && counter.getInteger("schemaVersion", 0) == 1
                && limitsCommitment.equals(counter.getString("policyCommitment"))
                && counter.get("count") instanceof Integer count && count >= 0 && count <= limits.maxReceipts();
    }
    private boolean expired(Document identity, Instant now) { return !identity.getDate("expiresAt").toInstant().isAfter(now); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private Instant canonicalTime(String value) {
        Instant instant = Instant.parse(value);
        if (!instant.toString().equals(value)) { throw invalid("TIME_INVALID"); }
        return instant;
    }
    private static Result result(Status status) { return new Result(status, null); }
    private static IllegalStateException invalid(String reason) { return new IllegalStateException("SOURCE_RECEIPT_" + reason); }
}
