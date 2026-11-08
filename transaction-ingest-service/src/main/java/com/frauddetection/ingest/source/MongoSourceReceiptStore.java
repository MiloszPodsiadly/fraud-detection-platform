package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceSignatureVerifier;


import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.frauddetection.ingest.source.MongoSourceReceiptSchema.*;
import static com.frauddetection.ingest.source.SourceReceiptStore.Status.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** Source receipt only: no Kafka publication, transaction payload persistence, or Device/Merchant history. */
final class MongoSourceReceiptStore implements SourceReceiptStore {
    static final int ATTEMPTS = 3;
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

    MongoSourceReceiptStore(MongoClient client, String databaseName, Clock clock, Limits limits, SourceSignatureVerifier verifier) {
        this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        this.verifier = Objects.requireNonNull(verifier);
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
        limitsCommitment = SourceReceiptCommitment.limits(limits);
        if (fresh) {
            capacity.insertOne(new Document("_id", "capacity").append("schemaVersion", 1)
                    .append("policyCommitment", limitsCommitment).append("count", 0));
        }
        Document counter = capacity.find(eq("_id", "capacity")).first();
        if (!validCounter(counter)) { throw invalid("CAPACITY_POLICY_INCOMPATIBLE"); }
    }

    @Override public Result admit(SourceSignatureVerifier.Verified evidence) {
        return authorized(evidence, session -> {
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
        });
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
