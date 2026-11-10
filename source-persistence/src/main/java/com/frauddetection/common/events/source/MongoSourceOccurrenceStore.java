package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.frauddetection.common.events.source.SourceOccurrenceSchema.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** One immutable scored snapshot per authenticated source + explicit run. Kafka delivery is at least once:
 * every retry uses the same scored ID/time/content, hence the existing alert occurrence fence can deduplicate it.
 * PUBLISHED means a broker ack was durably recorded, not that an alert consumer has processed the record.
 */
public final class MongoSourceOccurrenceStore {
    public static final String INITIAL_RUN = "initial";
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final TransactionOptions TX = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.MAJORITY).readPreference(ReadPreference.primary()).build();
    public enum Status { ABSENT, PENDING, PUBLISHING, PUBLISHED, RETRY_EXHAUSTED, UNAVAILABLE, CONFLICT, UNVERIFIED }
    public enum RedriveReason { BROKER_RECOVERED, OPERATOR_RECONCILIATION }
    public record Result(Status status, String id, TransactionScoredEvent event, String claimId) {
        public Result(Status status, String id, TransactionScoredEvent event) { this(status, id, event, null); }
        @Override public String toString() { return "SourceOccurrenceResult[" + status + "]"; }
    }
    public record RecoveryHealth(long pending, long publishing, long exhausted, long oldestPendingAgeSeconds) { }
    public record Lookup(SourceLineage.State state, SourceScoredOccurrence binding) {
        @Override public String toString() { return "SourceOccurrenceLookup[" + state + "]"; }
    }
    private final MongoClient client;
    private final MongoDatabase db;
    private final MongoSourceLineage lineage;
    private final Clock clock;
    private final SourceOccurrenceCipher cipher;
    private final Duration retention;

    public MongoSourceOccurrenceStore(MongoClient client, String database, String expectedUser, SourceSignatureVerifier policy,
                                      Clock clock, SourceOccurrenceCipher cipher, Duration payloadRetention) {
        this.client = Objects.requireNonNull(client); this.clock = Objects.requireNonNull(clock); this.cipher = Objects.requireNonNull(cipher);
        if (payloadRetention == null || payloadRetention.isNegative() || payloadRetention.isZero()
                || payloadRetention.compareTo(Duration.ofDays(1)) > 0 || payloadRetention.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("SOURCE_OCCURRENCE_RETENTION_INVALID");
        }
        retention = payloadRetention;
        lineage = new MongoSourceLineage(client, database, expectedUser, MongoSourceLineage.Stage.SCORER, policy, clock);
        lineage.requireOccurrenceWriter();
        db = client.getDatabase(database).withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.MAJORITY)
                .withReadPreference(ReadPreference.primary());
        SourceOccurrenceSchema.initialize(db, false);
    }

    public Result resume(TransactionEnrichedEvent input, String runId) {
        return transaction(session -> {
            SourceLineage authorization = lineage.enriched(session, input);
            if (authorization.state() != SourceLineage.State.VERIFIED) { return authorizationFailure(authorization); }
            String id = runKey(authorization.receiptId(), runId);
            Document row = db.getCollection(OCCURRENCES).find(session, eq("_id", id)).first();
            if (row == null) { return result(Status.ABSENT); }
            return restore(session, row, authorization.receiptId(), authorization.eventCommitment());
        });
    }

    public Result prepare(TransactionEnrichedEvent input, String runId, TransactionScoredEvent candidate) {
        return transaction(session -> {
            SourceLineage source = lineage.enriched(session, input);
            if (source.state() != SourceLineage.State.VERIFIED) { return authorizationFailure(source); }
            if (!sameInput(input, candidate)) { return result(Status.CONFLICT); }
            String id = runKey(source.receiptId(), runId);
            Document existing = db.getCollection(OCCURRENCES).find(session, eq("_id", id)).first();
            if (existing != null) { return restore(session, existing, source.receiptId(), source.eventCommitment()); }
            if (candidate.eventId() == null || !candidate.eventId().matches("[A-Za-z0-9._:-]{1,128}") || candidate.createdAt() == null) {
                return result(Status.CONFLICT);
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Instant expiry = now.plus(retention);
            Instant sourceExpiry = lineage.receiptExpiry(source.receiptId());
            if (sourceExpiry.isBefore(expiry)) { expiry = sourceExpiry; }
            if (!expiry.isAfter(now)) { return result(Status.UNAVAILABLE); }
            Document row = new Document("_id", id).append("schemaVersion", 2).append("receiptId", source.receiptId())
                    .append("enrichedCommitment", source.eventCommitment()).append("runId", runId).append("eventId", candidate.eventId())
                    .append("eventCreatedAt", candidate.createdAt().toString()).append("fingerprint", SourceScoredOccurrence.fingerprint(candidate))
                    .append("createdAt", Date.from(now)).append("expiresAt", Date.from(expiry)).append("state", "PENDING").append("attempts", 0)
                    .append("recoveryEpoch", 0).append("nextAttemptAt", Date.from(now));
            // The encrypted transport snapshot must survive the actual serializer/deserializer without changing occurrence identity.
            byte[] bytes = SourceScoredOccurrence.bytes(JSON, candidate);
            var decoded = new JacksonKafkaDeserializer<>(TransactionScoredEvent.class).deserialize("private-scored-outbox", bytes);
            if (!row.getString("fingerprint").equals(SourceScoredOccurrence.fingerprint(decoded))) { return result(Status.CONFLICT); }
            cipher.encrypt(row, bytes);
            Document collision = db.getCollection(OCCURRENCES).find(session,
                    and(eq("eventId", candidate.eventId()), eq("eventCreatedAt", candidate.createdAt().toString()))).first();
            if (collision != null) {
                db.getCollection(OCCURRENCES).updateOne(session, eq("_id", collision.getString("_id")), set("state", "CONFLICT"));
                return result(Status.CONFLICT);
            }
            var counters = db.getCollection(COUNTS);
            Document counter = counters.find(session, eq("_id", source.receiptId())).first();
            int existingRuns = db.getCollection(OCCURRENCES).find(session, eq("receiptId", source.receiptId()))
                    .projection(new Document("_id", 1)).hintString(SOURCE_RUN_INDEX).limit(MAX_RUNS + 1).into(new ArrayList<>()).size();
            if (existingRuns > MAX_RUNS || (counter == null ? existingRuns != 0
                    : counter.getInteger("schemaVersion", 0) != 1 || counter.getInteger("count", 0) != existingRuns)) { return result(Status.UNAVAILABLE); }
            if (counter == null) { counters.insertOne(session, new Document("_id", source.receiptId()).append("schemaVersion", 1).append("count", 1)); }
            else if (counter.getInteger("count") >= MAX_RUNS) { return result(Status.UNAVAILABLE); }
            else if (counters.updateOne(session, and(eq("_id", source.receiptId()), lt("count", MAX_RUNS)), inc("count", 1)).getModifiedCount() != 1) {
                throw invalid();
            }
            db.getCollection(OCCURRENCES).insertOne(session, row);
            return new Result(Status.PENDING, id, decoded);
        });
    }

    /** Each automatic attempt is durable and globally bounded; no body replay follows an unknown commit. */
    public Result claim(String id) {
        Result claimResult = transaction(session -> {
            Document row = db.getCollection(OCCURRENCES).find(session, eq("_id", id)).first();
            if (row == null) { return result(Status.UNAVAILABLE); }
            Result restored = restore(session, row, row.getString("receiptId"), row.getString("enrichedCommitment"));
            if (restored.status != Status.PENDING && restored.status != Status.PUBLISHING) { return restored; }
            Instant now = clock.instant();
            if ("PUBLISHING".equals(row.getString("state")) && row.get("leaseUntil", Date.class).toInstant().isAfter(now)) {
                return result(Status.UNAVAILABLE);
            }
            Date nextAttempt = row.get("nextAttemptAt", Date.class);
            if ("PENDING".equals(row.getString("state")) && nextAttempt != null && nextAttempt.toInstant().isAfter(now)) {
                return result(Status.UNAVAILABLE);
            }
            if (row.getInteger("attempts") >= 8) {
                Date nowDate = Date.from(now);
                var terminalized = db.getCollection(OCCURRENCES).updateOne(session,
                        and(eq("_id", id), gte("attempts", 8), or(
                                and(eq("state", "PENDING"),
                                        or(exists("leaseUntil", false), lte("leaseUntil", nowDate)),
                                        or(exists("nextAttemptAt", false), lte("nextAttemptAt", nowDate))),
                                and(eq("state", "PUBLISHING"), lte("leaseUntil", nowDate)))),
                        combine(set("state", "RETRY_EXHAUSTED"), set("lastAttemptCode", "BROKER_PUBLISH_FAILED"),
                                unset("leaseId"), unset("leaseUntil"), unset("nextAttemptAt")));
                if (!terminalized.wasAcknowledged() || terminalized.getMatchedCount() != 1 || terminalized.getModifiedCount() != 1) {
                    return result(Status.UNAVAILABLE);
                }
                return result(Status.RETRY_EXHAUSTED);
            }
            String claimId = java.util.UUID.randomUUID().toString();
            var claimed = db.getCollection(OCCURRENCES).updateOne(session,
                    and(eq("_id", id), eq("state", row.getString("state")), lt("attempts", 8)),
                    combine(inc("attempts", 1), set("state", "PUBLISHING"), set("leaseId", claimId),
                            set("leaseUntil", Date.from(now.plusSeconds(120)))));
            if (claimed.getModifiedCount() != 1) { throw invalid(); }
            return new Result(Status.PUBLISHING, id, restored.event, claimId);
        });
        if (claimResult.status() != Status.UNAVAILABLE) { return claimResult; }
        return readBackTerminalClaim(id);
    }

    /** Reconciles a failed CAS or unknown transaction commit before reporting an authoritative terminal state. */
    private Result readBackTerminalClaim(String id) {
        if (id == null) { return result(Status.UNAVAILABLE); }
        return transaction(session -> {
            Document row = db.getCollection(OCCURRENCES).find(session, eq("_id", id)).first();
            if (row == null) { return result(Status.UNAVAILABLE); }
            Result restored = restore(session, row, row.getString("receiptId"), row.getString("enrichedCommitment"));
            return restored.status() == Status.PUBLISHED || restored.status() == Status.RETRY_EXHAUSTED
                    ? restored : result(Status.UNAVAILABLE);
        });
    }

    /** Called only after the real publisher has returned its broker acknowledgement. */
    public Result confirm(String id, TransactionScoredEvent acknowledged, String claimId) {
        return transaction(session -> {
            Document row = db.getCollection(OCCURRENCES).find(session, eq("_id", id)).first();
            if (row == null) { return result(Status.UNAVAILABLE); }
            Result restored = restore(session, row, row.getString("receiptId"), row.getString("enrichedCommitment"));
            if (restored.event == null || !SourceScoredOccurrence.fingerprint(acknowledged).equals(row.getString("fingerprint"))) {
                return result(Status.CONFLICT);
            }
            if (restored.status == Status.PUBLISHED) { return restored; }
            if (restored.status != Status.PUBLISHING || row.getInteger("attempts") < 1 || claimId == null
                    || !claimId.equals(row.getString("leaseId"))) { return result(Status.UNAVAILABLE); }
            if (db.getCollection(OCCURRENCES).updateOne(session, and(eq("_id", id), eq("state", "PUBLISHING"), eq("leaseId", claimId)),
                    combine(set("state", "PUBLISHED"), unset("leaseId"), unset("leaseUntil"), unset("lastAttemptCode"))).getModifiedCount() != 1) { throw invalid(); }
            return new Result(Status.PUBLISHED, id, restored.event);
        });
    }

    public Result recordPublishFailure(String id, String claimId, String safeCode) {
        if (!Set.of("BROKER_PUBLISH_FAILED", "BROKER_ACK_UNRESOLVED").contains(safeCode)) { return result(Status.CONFLICT); }
        return transaction(session -> {
            Document row = db.getCollection(OCCURRENCES).find(session, eq("_id", id)).first();
            if (row == null || !"PUBLISHING".equals(row.getString("state")) || !Objects.equals(claimId, row.getString("leaseId"))) {
                return result(Status.UNAVAILABLE);
            }
            boolean exhausted = row.getInteger("attempts") >= 8;
            Instant now = clock.instant();
            long delay = Math.min(300, 15L << Math.min(4, Math.max(0, row.getInteger("attempts") - 1)));
            var updates = exhausted
                    ? combine(set("state", "RETRY_EXHAUSTED"), set("lastAttemptCode", safeCode), unset("leaseId"), unset("leaseUntil"))
                    : combine(set("state", "PENDING"), set("lastAttemptCode", safeCode), set("nextAttemptAt", Date.from(now.plusSeconds(delay))),
                            unset("leaseId"), unset("leaseUntil"));
            if (db.getCollection(OCCURRENCES).updateOne(session, and(eq("_id", id), eq("state", "PUBLISHING"), eq("leaseId", claimId)), updates)
                    .getModifiedCount() != 1) { return result(Status.UNAVAILABLE); }
            return result(exhausted ? Status.RETRY_EXHAUSTED : Status.PENDING);
        });
    }

    /** Controlled internal reconciliation; never exposed as an unauthenticated HTTP operation. */
    public Result redrive(String id, RedriveReason reason) {
        Objects.requireNonNull(reason, "SOURCE_OCCURRENCE_REDRIVE_REASON_REQUIRED");
        return transaction(session -> {
            Document row = db.getCollection(OCCURRENCES).find(session, eq("_id", id)).first();
            if (row == null || !"RETRY_EXHAUSTED".equals(row.getString("state"))) { return result(Status.UNAVAILABLE); }
            Result verified = restore(session, row, row.getString("receiptId"), row.getString("enrichedCommitment"));
            if (verified.status != Status.RETRY_EXHAUSTED) { return verified; }
            int epoch = row.getInteger("recoveryEpoch", 0);
            if (epoch >= 3) { return result(Status.UNAVAILABLE); }
            if (db.getCollection(OCCURRENCES).updateOne(session, and(eq("_id", id), eq("state", "RETRY_EXHAUSTED"),
                    or(lt("recoveryEpoch", 3), exists("recoveryEpoch", false))),
                    combine(set("state", "PENDING"), set("attempts", 0), set("recoveryEpoch", epoch + 1),
                            set("nextAttemptAt", Date.from(clock.instant())), set("lastRedriveAt", Date.from(clock.instant())),
                            set("redriveReason", reason.name()), unset("lastAttemptCode")))
                    .getModifiedCount() != 1) { return result(Status.UNAVAILABLE); }
            return new Result(Status.PENDING, id, verified.event);
        });
    }

    public Lookup lookup(TransactionScoredEvent event, SourceLineage authorization) {
        try {
            return lookup(new SourceOccurrenceAccess.Reference(event.eventId(), event.createdAt(), SourceScoredOccurrence.fingerprint(event)), authorization);
        } catch (RuntimeException unavailable) { return absent(); }
    }

    /** Returns a view bound to the owner's expected signed purpose and audience. */
    public SourceOccurrenceAccess readOnly(SourceSignatureVerifier.Context expectedContext) {
        Objects.requireNonNull(expectedContext, "SOURCE_OCCURRENCE_CONTEXT_REQUIRED");
        return (authorization, occurrence) -> {
            if (authorization == null || authorization.state() != SourceLineage.State.VERIFIED || occurrence == null
                    || !expectedContext.purpose().equals(authorization.source().purpose())
                    || !expectedContext.audience().equals(authorization.source().audience())) { return Optional.empty(); }
            return Optional.ofNullable(lookup(occurrence, authorization).binding());
        };
    }

    private Lookup lookup(SourceOccurrenceAccess.Reference occurrence, SourceLineage authorization) {
        try {
            if (authorization == null || authorization.state() != SourceLineage.State.VERIFIED) { return absent(); }
            Document row = db.getCollection(OCCURRENCES).find(and(eq("eventId", occurrence.eventId()),
                    eq("eventCreatedAt", occurrence.createdAt().toString()))).first();
            if (row == null || !authorization.receiptId().equals(row.getString("receiptId"))
                    || !occurrence.fingerprint().equals(row.getString("fingerprint"))
                    || !authorization.eventCommitment().equals(row.getString("enrichedCommitment"))) { return absent(); }
            Result result = restore(row, authorization.receiptId());
            if (result.status != Status.PUBLISHED) { return absent(); }
            SourceLineage current = lineage.recoverEnriched(row.getString("receiptId"), row.getString("enrichedCommitment"));
            if (current.state() != SourceLineage.State.VERIFIED || !current.source().equals(authorization.source())
                    || !current.eventCommitment().equals(authorization.eventCommitment())) { return absent(); }
            return new Lookup(SourceLineage.State.VERIFIED, new SourceScoredOccurrence(current.source(), current.receiptId(),
                    current.eventCommitment(), occurrence.eventId(), occurrence.createdAt(), row.getString("fingerprint")));
        } catch (RuntimeException unavailable) { return absent(); }
    }

    public List<String> pending(int limit) {
        bounded(limit);
        Date now = Date.from(clock.instant());
        return db.getCollection(OCCURRENCES).find(and(gt("expiresAt", now), or(
                        and(eq("state", "PENDING"), lt("attempts", 8), or(lte("nextAttemptAt", now), exists("nextAttemptAt", false))),
                        and(eq("state", "PUBLISHING"), lte("leaseUntil", now)))))
                .sort(new Document("expiresAt", 1)).limit(limit).map(row -> row.getString("_id")).into(new ArrayList<>());
    }

    public RecoveryHealth recoveryHealth() {
        Date now = Date.from(clock.instant());
        long pending = db.getCollection(OCCURRENCES).countDocuments(and(eq("state", "PENDING"), gt("expiresAt", now), lt("attempts", 8)));
        long publishing = db.getCollection(OCCURRENCES).countDocuments(and(eq("state", "PUBLISHING"), gt("expiresAt", now)));
        long exhausted = db.getCollection(OCCURRENCES).countDocuments(and(gt("expiresAt", now), or(
                eq("state", "RETRY_EXHAUSTED"), and(eq("state", "PENDING"), gte("attempts", 8)))));
        Document oldest = db.getCollection(OCCURRENCES).find(and(eq("state", "PENDING"), gt("expiresAt", now), lt("attempts", 8)))
                .sort(new Document("createdAt", 1)).projection(new Document("createdAt", 1)).first();
        long age = oldest == null ? 0 : Math.max(0, Duration.between(oldest.getDate("createdAt").toInstant(), clock.instant()).toSeconds());
        return new RecoveryHealth(pending, publishing, exhausted, age);
    }
    /** Ciphertext is removed after its explicit retention; the exact run/occurrence replay fence remains. */
    public int compactExpired(int limit) {
        bounded(limit);
        var rows = db.getCollection(OCCURRENCES).find(and(ne("state", "EXPIRED"), lte("expiresAt", Date.from(clock.instant()))))
                .limit(limit).into(new ArrayList<>());
        int count = 0;
        for (Document row : rows) {
            count += (int) db.getCollection(OCCURRENCES).updateOne(and(eq("_id", row.getString("_id")), lte("expiresAt", Date.from(clock.instant()))),
                    combine(set("state", "EXPIRED"), unset("ciphertext"), unset("nonce"), unset("keyId"))).getModifiedCount();
        }
        return count;
    }

    private Result restore(Document row, String authorizedReceipt) {
        return restore(row, authorizedReceipt, row.getString("enrichedCommitment"), lineage::recoverEnriched);
    }
    private Result restore(ClientSession session, Document row, String authorizedReceipt, String expectedCommitment) {
        return restore(row, authorizedReceipt, expectedCommitment,
                (receipt, commitment) -> lineage.recoverEnriched(session, receipt, commitment));
    }
    private Result restore(Document row, String authorizedReceipt, String expectedCommitment,
                           BiFunction<String, String, SourceLineage> currentLineage) {
        String state = row.getString("state");
        if ("CONFLICT".equals(state)) { return result(Status.CONFLICT); }
        if ("EXPIRED".equals(state)) { return result(Status.UNAVAILABLE); }
        String receiptId = row.getString("receiptId");
        String runId = row.getString("runId");
        try {
            if (!Set.of(1, 2).contains(row.getInteger("schemaVersion", 0)) || authorizedReceipt == null || !authorizedReceipt.equals(receiptId)
                    || runId == null || !runKey(authorizedReceipt, runId).equals(row.getString("_id"))) { return result(Status.CONFLICT); }
        } catch (RuntimeException malformedIdentity) { return result(Status.CONFLICT); }
        if (!Set.of("PENDING", "PUBLISHING", "PUBLISHED", "RETRY_EXHAUSTED").contains(state)) { return result(Status.CONFLICT); }
        if (expectedCommitment == null || !expectedCommitment.equals(row.getString("enrichedCommitment"))) {
            return result(Status.CONFLICT);
        }
        if (!(row.get("expiresAt") instanceof Date expires) || !(row.get("createdAt") instanceof Date created)) {
            return result(Status.CONFLICT);
        }
        if (!expires.toInstant().isAfter(clock.instant()) || created.toInstant().isAfter(clock.instant())) { return result(Status.UNAVAILABLE); }
        SourceLineage current = currentLineage.apply(authorizedReceipt, row.getString("enrichedCommitment"));
        if (current.state() != SourceLineage.State.VERIFIED) { return authorizationFailure(current); }
        var event = new JacksonKafkaDeserializer<>(TransactionScoredEvent.class).deserialize("private-scored-outbox", cipher.decrypt(row));
        if (!event.eventId().equals(row.getString("eventId")) || !event.createdAt().toString().equals(row.getString("eventCreatedAt"))
                || !SourceScoredOccurrence.fingerprint(event).equals(row.getString("fingerprint"))) { return result(Status.CONFLICT); }
        return new Result(Status.valueOf(row.getString("state")), row.getString("_id"), event);
    }
    private static Result authorizationFailure(SourceLineage source) {
        return result(switch (source.state()) {
            case CONFLICT -> Status.CONFLICT;
            case UNAVAILABLE -> Status.UNAVAILABLE;
            case UNVERIFIED, REVOKED -> Status.UNVERIFIED;
            case VERIFIED -> throw new IllegalArgumentException("SOURCE_OCCURRENCE_AUTHORIZATION_STATE_INVALID");
        });
    }
    private static boolean sameInput(TransactionEnrichedEvent a, TransactionScoredEvent b) {
        return b != null && Objects.equals(a.transactionId(), b.transactionId()) && Objects.equals(a.correlationId(), b.correlationId())
                && Objects.equals(a.customerId(), b.customerId()) && Objects.equals(a.accountId(), b.accountId())
                && Objects.equals(a.transactionTimestamp(), b.transactionTimestamp()) && Objects.equals(a.transactionAmount(), b.transactionAmount())
                && Objects.equals(a.merchantInfo(), b.merchantInfo()) && Objects.equals(a.deviceInfo(), b.deviceInfo())
                && Objects.equals(a.locationInfo(), b.locationInfo()) && Objects.equals(a.customerContext(), b.customerContext())
                && Objects.equals(a.featureSnapshot(), b.featureSnapshot());
    }
    private Result transaction(Function<ClientSession, Result> operation) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TX);
                try {
                    Result result = operation.apply(session);
                    for (int commit = 0; ; commit++) {
                        try { session.commitTransaction(); return result; }
                        catch (MongoException failure) {
                            if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) || commit == 2) { throw failure; }
                        }
                    }
                } catch (RuntimeException failure) {
                    if (failure instanceof MongoException mongo && mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                        return result(Status.UNAVAILABLE);
                    }
                    try { if (session.hasActiveTransaction()) { session.abortTransaction(); } } catch (RuntimeException ignored) { }
                    if (failure instanceof MongoException mongo && (mongo.getCode() == 11000
                            || mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) && attempt < 2) { continue; }
                    return result(Status.UNAVAILABLE);
                }
            } catch (RuntimeException unavailable) { return result(Status.UNAVAILABLE); }
        }
        return result(Status.UNAVAILABLE);
    }
    private static String runKey(String receipt, String run) { SourceScope.requireReference(run); return SourceReceiptIdentity.digest("source-scored-run/v1", receipt, run); }
    private static Result result(Status state) { return new Result(state, null, null); }
    private static Lookup absent() { return new Lookup(SourceLineage.State.UNAVAILABLE, null); }
    private static void bounded(int limit) { if (limit < 1 || limit > 100) { throw new IllegalArgumentException("SOURCE_OCCURRENCE_LIMIT_INVALID"); } }
    private static IllegalStateException invalid() { return new IllegalStateException("SOURCE_OCCURRENCE_WRITE_UNRESOLVED"); }
}
