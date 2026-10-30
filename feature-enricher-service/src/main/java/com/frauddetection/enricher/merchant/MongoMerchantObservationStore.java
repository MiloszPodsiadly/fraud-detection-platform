package com.frauddetection.enricher.merchant;

import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Updates.*;

/** Package-internal and not a Spring component. There is no production verifier, factory, connection or importer. */
final class MongoMerchantObservationStore implements MerchantObservationStore {
    private static final int ATTEMPTS = 3;
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY)
            .readPreference(ReadPreference.primary()).build();
    private final MongoClient client;
    private final MongoCollection<Document> candidates;
    private final MongoCollection<Document> history;
    private final MongoCollection<Document> coverage;
    private final Clock clock;
    private final Limits limits;
    private final AdmissionVerifier admission;
    private final String policyCommitment;

    MongoMerchantObservationStore(MongoClient client, String databaseName, Clock clock, Limits limits, AdmissionVerifier admission) {
        if (admission == null) { throw failure("ADMISSION_REQUIRED"); }
        this.admission = admission;
        this.client = Objects.requireNonNull(client, "client is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.limits = Objects.requireNonNull(limits, "limits are required");
        if (databaseName == null || !databaseName.matches("[a-z][a-z0-9_]{0,63}")) { throw failure("DATABASE_NAME"); }
        policyCommitment = hash(limits.window(), limits.historyRetention(), limits.receiptRetention(), limits.replayHorizon(),
                limits.clockSkew(), limits.maxHistoryRows(), limits.maxPending(), limits.maxSealBatch(), limits.maxStoredObservations());
        MongoDatabase database = client.getDatabase(databaseName).withReadConcern(ReadConcern.MAJORITY)
                .withWriteConcern(WriteConcern.MAJORITY).withReadPreference(ReadPreference.primary());
        Long timeout = database.getTimeout(TimeUnit.MILLISECONDS);
        if (timeout == null || timeout <= 0 || timeout > 5_000) { throw failure("OPERATION_TIMEOUT_REQUIRED"); }
        try {
            MongoMerchantObservationSchema.initialize(database);
        } catch (MongoException exception) { throw failure("SCHEMA_UNAVAILABLE"); }
        candidates = database.getCollection(MongoMerchantObservationSchema.CANDIDATES);
        history = database.getCollection(MongoMerchantObservationSchema.HISTORY);
        coverage = database.getCollection(MongoMerchantObservationSchema.COVERAGE);
    }

    @Override
    public StageResult stage(Observation observation) {
        authorize(() -> admission.requireObservation(Objects.requireNonNull(observation)));
        Instant now = now();
        if (observation.eventTime().isAfter(now.plus(limits.clockSkew()))
                || observation.sourceObservedAt().isAfter(now.plus(limits.clockSkew()))) { throw failure("FUTURE_EVENT"); }
        if (!observation.eventTime().isAfter(now.minus(limits.replayHorizon()))) { throw failure("REPLAY_EXPIRED"); }
        try { return stageAuthorized(observation, now); }
        catch (IllegalStateException exception) {
            if ("MERCHANT_STORE_IDENTITY_CONFLICT".equals(exception.getMessage())) {
                try {
                    transaction(session -> {
                        Document source = source(session, observation.scope());
                        if (source != null) {
                            requireSource(source, observation.scope());
                            if (!invalidated(source)) { invalidate(session, observation.scope()); }
                        }
                        return null;
                    });
                } catch (MongoException unresolved) { throw failure("CONFLICT_UNRESOLVED"); }
            }
            throw exception;
        }
    }

    private StageResult stageAuthorized(Observation observation, Instant now) {
        try {
            StageDecision decision = transaction(session -> {
                Document source = source(session, observation.scope());
                Document existing = candidates.find(session, and(eq("institution", observation.scope().institution()),
                                eq("source", observation.scope().source()), eq("sourceEvent", observation.sourceEvent())))
                        .hintString("merchant_event_unique").limit(1).first();
                if (existing != null) {
                    requireSource(source, observation.scope());
                    return new StageDecision(existingStage(existing, observation), Capacity.NONE);
                }
                if (source == null) {
                    if (candidates.find(session, eq("scope", scopeId(observation.scope()))).hintString("merchant_seal_batch").limit(1).first() != null
                            || history.find(session, eq("scope", scopeId(observation.scope())))
                            .hintString(MongoMerchantObservationSchema.HISTORY_WINDOW).limit(1).first() != null) {
                        throw failure("COVERAGE_MISSING");
                    }
                    source = sourceDocument(observation.scope());
                    coverage.insertOne(session, source);
                } else { requireSource(source, observation.scope()); }
                if (invalidated(source)) {
                    return new StageDecision(StageResult.QUARANTINED, Capacity.NONE);
                }
                if (source.getInteger("pending") >= limits.maxPending()) {
                    invalidate(session, observation.scope());
                    return new StageDecision(null, Capacity.PENDING);
                }
                if (source.getInteger("accepted") >= limits.maxStoredObservations()) {
                    invalidate(session, observation.scope());
                    return new StageDecision(null, Capacity.STORAGE);
                }
                if (!completeMembership(session, source, observation.scope())) {
                    invalidate(session, observation.scope());
                    return new StageDecision(StageResult.QUARANTINED, Capacity.NONE);
                }
                // Hints fail closed if required indexes disappear. Atomic unique indexes still arbitrate concurrent inserts.
                if (hasIdentityConflict(session, observation)) { throw failure("IDENTITY_CONFLICT"); }
                boolean late = source.getDate("watermark") != null
                        && !observation.eventTime().isAfter(instant(source, "watermark"));
                Document candidate = observationDocument(observation).append("status", late ? "QUARANTINED" : "STAGED")
                        .append("stagedAt", Date.from(now));
                if (late) {
                    candidate.append("receiptExpireAt", Date.from(now.plus(limits.receiptRetention())))
                            .append("terminalReason", "POST_SEAL_EVENT");
                }
                candidates.insertOne(session, candidate);
                coverage.updateOne(session, eq("_id", source.getString("_id")), late
                        ? combine(inc("revision", 1L), inc("accepted", 1), inc("quarantined", 1), set("invalidated", true))
                        : combine(inc("revision", 1L), inc("accepted", 1), inc("pending", 1)));
                return new StageDecision(late ? StageResult.QUARANTINED : StageResult.CREATED, Capacity.NONE);
            });
            if (decision.capacity() == Capacity.PENDING) { throw failure("PENDING_CAPACITY"); }
            if (decision.capacity() == Capacity.STORAGE) { throw failure("STORAGE_CAPACITY"); }
            return decision.result();
        } catch (MongoException exception) {
            Document durable = safeRead(candidates, eventId(observation));
            if (durable != null) { return existingStage(durable, observation); }
            if (hasIdentityConflict(null, observation)) { throw failure("IDENTITY_CONFLICT"); }
            throw failure("STAGE_UNRESOLVED");
        }
    }

    @Override
    public void seal(CoverageSeal seal) {
        authorize(() -> admission.requireCoverage(Objects.requireNonNull(seal)));
        if (seal.watermark().isAfter(now().plus(limits.clockSkew()))) { throw failure("FUTURE_WATERMARK"); }
        try {
            transaction(session -> {
                Document source = source(session, seal.scope());
                requireSource(source, seal.scope());
                if (invalidated(source)) { throw failure("COVERAGE_INVALIDATED"); }
                if (!completeMembership(session, source, seal.scope())) { throw failure("HISTORY_MISSING"); }
                if (matchesSeal(source, seal)) { return null; }
                long previous = source.getLong("sealedSequence");
                long delta = seal.throughSequence() - previous;
                if (delta <= 0 || delta > limits.maxSealBatch()
                        || source.getDate("watermark") != null && seal.watermark().isBefore(instant(source, "watermark"))
                        || source.getDate("coverageStart") != null && seal.coverageStart().isBefore(instant(source, "coverageStart"))) {
                    throw failure("SEAL_REGRESSION");
                }
                List<Document> batch = candidates.find(session, and(eq("scope", scopeId(seal.scope())),
                                gt("sequence", previous), lte("sequence", seal.throughSequence())))
                        .sort(ascending("sequence")).hintString("merchant_seal_batch")
                        .limit(limits.maxSealBatch() + 1).into(new ArrayList<>());
                if (batch.size() != delta) { throw failure("SOURCE_SEQUENCE_GAP"); }
                long expected = previous;
                for (Document row : batch) {
                    Observation observation = decodeObservation(row);
                    if (observation.sequence() != ++expected || observation.eventTime().isAfter(seal.watermark())
                            || "QUARANTINED".equals(row.getString("status"))) { throw failure("SOURCE_SEQUENCE_GAP"); }
                }
                if (coverage.updateOne(session, and(eq("_id", source.getString("_id")), eq("revision", source.getLong("revision"))),
                        combine(set("sealedSequence", seal.throughSequence()), set("coverageStart", Date.from(seal.coverageStart())),
                                set("watermark", Date.from(seal.watermark())), inc("revision", 1L))).getModifiedCount() != 1) {
                    throw race();
                }
                return null;
            });
        } catch (MongoException exception) {
            if (!matchesSeal(safeRead(coverage, scopeId(seal.scope())), seal)) { throw failure("SEAL_UNRESOLVED"); }
        }
    }

    @Override
    public CommitResult commit(Observation observation) {
        authorize(() -> admission.requireObservation(Objects.requireNonNull(observation)));
        // The commit response contains historic counts, so observation/write admission alone is insufficient.
        authorize(() -> admission.requireRead(observation.scope()));
        try {
            return transaction(session -> {
                Document source = source(session, observation.scope());
                requireSource(source, observation.scope());
                Document candidate = candidates.find(session, eq("_id", eventId(observation))).first();
                requireBinding(candidate, observation);
                requireReceiptFresh(candidate);
                if (!completeMembership(session, source, observation.scope())) {
                    if ("STAGED".equals(candidate.getString("status"))) {
                        return quarantine(session, candidate, observation.scope(), Reason.HISTORY_MISSING);
                    }
                    if (!invalidated(source)) { invalidate(session, observation.scope()); }
                    return "COMMITTED".equals(candidate.getString("status"))
                            ? new CommitResult(CommitStatus.EXISTING, unavailable(observation, Reason.HISTORY_MISSING))
                            : new CommitResult(CommitStatus.QUARANTINED, null);
                }
                CommitResult terminal = terminalResult(candidate, observation, source);
                if (terminal != null) { return terminal; }
                if (invalidated(source)) {
                    return quarantine(session, candidate, observation.scope(), Reason.INCOMPLETE_COVERAGE);
                }
                if (!observation.eventTime().isAfter(now().minus(limits.replayHorizon()))) {
                    return quarantine(session, candidate, observation.scope(), Reason.RETENTION_EXPIRED);
                }
                if (source.getDate("watermark") == null || source.getLong("sealedSequence") < observation.sequence()
                        || instant(source, "watermark").isBefore(observation.eventTime())) {
                    return new CommitResult(CommitStatus.WAITING_FOR_COVERAGE, null);
                }
                Instant start = observation.eventTime().minus(limits.window());
                Bson window = window(observation, start);
                if (candidates.find(session, and(window, eq("status", "STAGED")))
                        .hintString(MongoMerchantObservationSchema.CANDIDATE_WINDOW).limit(1).first() != null) {
                    return new CommitResult(CommitStatus.WAITING_FOR_PREDECESSOR, null);
                }
                Snapshot snapshot;
                if (start.isBefore(now().minus(limits.historyRetention()))) {
                    snapshot = unavailable(observation, Reason.RETENTION_EXPIRED);
                } else if (start.isBefore(instant(source, "coverageStart"))) {
                    snapshot = unavailable(observation, Reason.INCOMPLETE_COVERAGE);
                } else {
                    List<Document> rows = history.find(session, window).sort(ascending("eventTime", "sequence"))
                            .hintString(MongoMerchantObservationSchema.HISTORY_WINDOW).limit(limits.maxHistoryRows() + 1)
                            .into(new ArrayList<>());
                    List<Document> expected = candidates.find(session, and(window, eq("status", "COMMITTED")))
                            .sort(ascending("eventTime", "sequence")).hintString(MongoMerchantObservationSchema.CANDIDATE_WINDOW)
                            .limit(limits.maxHistoryRows() + 1).into(new ArrayList<>());
                    if (rows.size() > limits.maxHistoryRows() || expected.size() > limits.maxHistoryRows()) {
                        return quarantine(session, candidate, observation.scope(), Reason.CAPACITY_EXCEEDED);
                    }
                    if (!matchesHistory(rows, expected)) {
                        return quarantine(session, candidate, observation.scope(), Reason.HISTORY_MISSING);
                    }
                    snapshot = new Snapshot(start, observation.eventTime(), Availability.COMPLETE_WINDOW, rows.size(), Reason.NONE);
                }
                history.insertOne(session, observationDocument(observation)
                        .append("expireAt", Date.from(later(now(), observation.eventTime()).plus(limits.historyRetention()))));
                if (candidates.updateOne(session, and(eq("_id", eventId(observation)), eq("status", "STAGED")),
                        combine(set("status", "COMMITTED"), set("receiptExpireAt", Date.from(now().plus(limits.receiptRetention()))),
                                set("windowStart", Date.from(snapshot.windowStart())), set("asOf", Date.from(snapshot.asOf())),
                                set("availability", snapshot.availability().name()), set("priorCount", snapshot.priorCount()),
                                set("reason", snapshot.reason().name()), set("snapshotCommitment", snapshotCommitment(observation, snapshot))))
                        .getModifiedCount() != 1) { throw race(); }
                transitionSource(session, observation.scope(), false);
                return new CommitResult(CommitStatus.COMMITTED, snapshot);
            });
        } catch (MongoException exception) {
            Document durable = safeRead(candidates, eventId(observation));
            if (durable != null) {
                requireBinding(durable, observation);
                requireReceiptFresh(durable);
                Document source = safeRead(coverage, scopeId(observation.scope()));
                requireSource(source, observation.scope());
                if ("COMMITTED".equals(durable.getString("status"))) {
                    return new CommitResult(CommitStatus.EXISTING, readSnapshot(observation));
                }
                CommitResult reconciled = terminalResult(durable, observation, source);
                if (reconciled != null) { return reconciled; }
            }
            throw failure("COMMIT_UNRESOLVED");
        }
    }

    @Override
    public Snapshot readSnapshot(Observation observation) {
        authorize(() -> admission.requireObservation(Objects.requireNonNull(observation)));
        authorize(() -> admission.requireRead(observation.scope()));
        try (ClientSession session = client.startSession()) {
            session.startTransaction(TRANSACTION);
            try {
                Document source = source(session, observation.scope());
                requireSource(source, observation.scope());
                Document row = candidates.find(session, eq("_id", eventId(observation))).first();
                requireBinding(row, observation);
                requireReceiptFresh(row);
                if (!"COMMITTED".equals(row.getString("status"))) { throw failure("SNAPSHOT_MISSING"); }
                Snapshot stored = decodeSnapshot(row, observation);
                if (!completeMembership(session, source, observation.scope())) { return unavailable(observation, Reason.HISTORY_MISSING); }
                return currentSnapshot(stored, source, observation);
            } finally { abort(session); } // A read-only snapshot needs no commit acknowledgment or write retry.
        } catch (MongoException exception) { throw failure("READ_UNAVAILABLE"); }
    }

    @Override
    public PendingCounts pending(Scope scope) {
        authorize(() -> admission.requireRead(Objects.requireNonNull(scope)));
        Document row = safeRead(coverage, scopeId(scope));
        if (row == null) { return new PendingCounts(0, 0); }
        requireSource(row, scope);
        return new PendingCounts(row.getInteger("pending"), row.getInteger("quarantined"));
    }

    private CommitResult quarantine(ClientSession session, Document candidate, Scope scope, Reason reason) {
        if (candidates.updateOne(session, and(eq("_id", candidate.getString("_id")), eq("status", "STAGED")),
                combine(set("status", "QUARANTINED"), set("terminalReason", reason.name()),
                        set("receiptExpireAt", Date.from(now().plus(limits.receiptRetention()))))).getModifiedCount() != 1) {
            throw race();
        }
        transitionSource(session, scope, true);
        return new CommitResult(CommitStatus.QUARANTINED, null);
    }

    private void transitionSource(ClientSession session, Scope scope, boolean quarantined) {
        Bson update = quarantined
                ? combine(inc("revision", 1L), inc("pending", -1), inc("quarantined", 1), set("invalidated", true))
                : combine(inc("revision", 1L), inc("pending", -1));
        if (coverage.updateOne(session, and(eq("_id", scopeId(scope)), gt("pending", 0)), update).getModifiedCount() != 1) {
            throw failure("PENDING_COUNT_INVARIANT");
        }
    }

    private void invalidate(ClientSession session, Scope scope) {
        if (coverage.updateOne(session, eq("_id", scopeId(scope)), combine(set("invalidated", true), inc("revision", 1L)))
                .getModifiedCount() != 1) { throw failure("COVERAGE_MISSING"); }
    }

    private boolean matchesHistory(List<Document> rows, List<Document> expected) {
        if (rows.size() != expected.size()) { return false; }
        for (int index = 0; index < rows.size(); index++) {
            Document row = rows.get(index);
            Document receipt = expected.get(index);
            if (!Objects.equals(row.getString("_id"), receipt.getString("_id"))
                    || !Objects.equals(row.getString("inputCommitment"), receipt.getString("inputCommitment"))
                    || row.getDate("expireAt") == null || !instant(row, "expireAt").isAfter(now())
                    || !decodeObservation(row).equals(decodeObservation(receipt))) { return false; }
        }
        return true;
    }

    /** Bounded, indexed receipt accounting. Receipt expiry/removal retires completeness; an old epoch is never silently reseeded. */
    private boolean completeMembership(ClientSession session, Document source, Scope scope) {
        Bson retained = and(eq("scope", scopeId(scope)), or(eq("status", "STAGED"), gt("receiptExpireAt", Date.from(now()))));
        long count = candidates.countDocuments(session, retained, new com.mongodb.client.model.CountOptions()
                .hintString("merchant_seal_batch").limit(limits.maxStoredObservations() + 1));
        return count == source.getInteger("accepted");
    }

    private Snapshot unavailable(Observation observation, Reason reason) {
        return new Snapshot(observation.eventTime().minus(limits.window()), observation.eventTime(), Availability.UNAVAILABLE, null, reason);
    }

    private CommitResult terminalResult(Document row, Observation observation, Document source) {
        return switch (row.getString("status")) {
            case "COMMITTED" -> new CommitResult(CommitStatus.EXISTING,
                    currentSnapshot(decodeSnapshot(row, observation), source, observation));
            case "QUARANTINED" -> new CommitResult(CommitStatus.QUARANTINED, null);
            case "STAGED" -> null;
            default -> throw failure("CANDIDATE_STATE");
        };
    }

    /** Revoke usability without rewriting the immutable receipt when coverage or retained history is no longer sufficient. */
    private Snapshot currentSnapshot(Snapshot stored, Document source, Observation observation) {
        if (invalidated(source) || source.getDate("watermark") == null
                || source.getLong("sealedSequence") < observation.sequence()
                || instant(source, "watermark").isBefore(observation.eventTime())
                || instant(source, "coverageStart").isAfter(stored.windowStart())) {
            return unavailable(observation, Reason.INCOMPLETE_COVERAGE);
        }
        if (stored.windowStart().isBefore(now().minus(limits.historyRetention()))) {
            return unavailable(observation, Reason.RETENTION_EXPIRED);
        }
        return stored;
    }

    private StageResult existingStage(Document row, Observation observation) {
        requireBinding(row, observation);
        requireReceiptFresh(row);
        return "QUARANTINED".equals(row.getString("status")) ? StageResult.QUARANTINED : StageResult.EXISTING;
    }

    private void requireBinding(Document row, Observation observation) {
        if (row == null) { throw failure("CANDIDATE_MISSING"); }
        if (!observation.equals(decodeObservation(row))) { throw failure("IDENTITY_CONFLICT"); }
    }

    private void requireReceiptFresh(Document row) {
        try {
            String status = row.getString("status");
            if (!"STAGED".equals(status) && !"COMMITTED".equals(status) && !"QUARANTINED".equals(status)) {
                throw failure("CANDIDATE_STATE");
            }
            if (!"STAGED".equals(status)
                    && (row.getDate("receiptExpireAt") == null || !instant(row, "receiptExpireAt").isAfter(now()))) {
                throw failure("RECEIPT_EXPIRED");
            }
        } catch (IllegalStateException exception) { throw exception; }
        catch (RuntimeException exception) { throw failure("CORRUPT_RECEIPT"); }
    }

    private Snapshot decodeSnapshot(Document row, Observation observation) {
        try {
            Snapshot snapshot = new Snapshot(instant(row, "windowStart"), instant(row, "asOf"),
                    Availability.valueOf(row.getString("availability")), row.getInteger("priorCount"), Reason.valueOf(row.getString("reason")));
            if (!snapshot.asOf().equals(observation.eventTime())
                    || !snapshot.windowStart().equals(observation.eventTime().minus(limits.window()))
                    || snapshot.priorCount() != null && snapshot.priorCount() > limits.maxHistoryRows()
                    || !snapshotCommitment(observation, snapshot).equals(row.getString("snapshotCommitment"))) {
                throw failure("CORRUPT_SNAPSHOT");
            }
            return snapshot;
        } catch (RuntimeException exception) { throw failure("CORRUPT_SNAPSHOT"); }
    }

    private Observation decodeObservation(Document row) {
        try {
            Observation observation = new Observation(decodeScope(row), row.getString("sourceEvent"), row.getString("transaction"),
                    row.getString("occurrence"), row.getString("merchant"), row.getLong("sequence"), instant(row, "eventTime"),
                    instant(row, "sourceObservedAt"), row.getString("payloadCommitment"));
            if (!eventId(observation).equals(row.getString("_id")) || !scopeId(observation.scope()).equals(row.getString("scope"))
                    || !inputCommitment(observation).equals(row.getString("inputCommitment"))) { throw failure("CORRUPT_OBSERVATION"); }
            return observation;
        } catch (RuntimeException exception) { throw failure("CORRUPT_OBSERVATION"); }
    }

    private void requireSource(Document row, Scope scope) {
        if (row == null) { throw failure("COVERAGE_MISSING"); }
        try {
            if (!scope.equals(decodeScope(row)) || !scopeId(scope).equals(row.getString("_id"))) { throw failure("SCOPE_CONFLICT"); }
            if (!policyCommitment.equals(row.getString("policyCommitment"))) { throw failure("POLICY_MISMATCH"); }
            if (row.getInteger("pending") < 0 || row.getInteger("quarantined") < 0 || row.getLong("sealedSequence") < 0
                    || row.getLong("revision") < 0 || row.getBoolean("invalidated") == null) { throw failure("CORRUPT_COVERAGE"); }
            int accepted = row.getInteger("accepted");
            if (accepted < 0 || accepted > limits.maxStoredObservations() || accepted < row.getLong("sealedSequence")
                    || row.getInteger("pending") + row.getInteger("quarantined") > accepted
                    || (row.getDate("coverageStart") == null) != (row.getDate("watermark") == null)
                    || (row.getLong("sealedSequence") == 0) != (row.getDate("watermark") == null)) {
                throw failure("CORRUPT_COVERAGE");
            }
            if (row.getDate("watermark") != null) {
                new CoverageSeal(scope, row.getLong("sealedSequence"), instant(row, "coverageStart"), instant(row, "watermark"));
            }
        } catch (IllegalStateException exception) { throw exception; }
        catch (RuntimeException exception) { throw failure("CORRUPT_COVERAGE"); }
    }

    private static Scope decodeScope(Document row) {
        if (!Integer.valueOf(1).equals(row.get("schemaVersion"))) { throw failure("SCHEMA_VERSION"); }
        return new Scope(row.getString("issuer"), row.getString("institution"), row.getString("source"), row.getString("epoch"),
                row.getInteger("identityVersion"));
    }

    private Document source(ClientSession session, Scope scope) { return coverage.find(session, eq("_id", scopeId(scope))).first(); }

    private Document sourceDocument(Scope scope) {
        return scopeDocument(scope).append("_id", scopeId(scope)).append("revision", 0L).append("pending", 0)
                .append("accepted", 0).append("quarantined", 0).append("sealedSequence", 0L).append("invalidated", false)
                .append("policyCommitment", policyCommitment);
    }

    private static Document scopeDocument(Scope scope) {
        return new Document("schemaVersion", 1).append("issuer", scope.issuer()).append("institution", scope.institution())
                .append("source", scope.source()).append("epoch", scope.epoch()).append("identityVersion", scope.identityVersion());
    }

    private static Document observationDocument(Observation observation) {
        return scopeDocument(observation.scope()).append("_id", eventId(observation)).append("scope", scopeId(observation.scope()))
                .append("sourceEvent", observation.sourceEvent()).append("transaction", observation.transaction())
                .append("occurrence", observation.occurrence()).append("merchant", observation.merchant())
                .append("sequence", observation.sequence()).append("eventTime", Date.from(observation.eventTime()))
                .append("sourceObservedAt", Date.from(observation.sourceObservedAt())).append("payloadCommitment", observation.payloadCommitment())
                .append("inputCommitment", inputCommitment(observation));
    }

    private boolean hasIdentityConflict(ClientSession session, Observation observation) {
        try {
            for (String identity : List.of("transaction", "occurrence", "sequence")) {
                Bson key = and(eq("institution", observation.scope().institution()), eq("source", observation.scope().source()),
                        "sequence".equals(identity) ? and(eq("epoch", observation.scope().epoch()), eq("sequence", observation.sequence()))
                                : eq(identity, "transaction".equals(identity) ? observation.transaction() : observation.occurrence()));
                var query = session == null ? candidates.find(key) : candidates.find(session, key);
                if (query.hintString("merchant_" + identity + "_unique").limit(1).first() != null) { return true; }
            }
            return false;
        } catch (MongoException exception) {
            if (session != null) { throw exception; }
            throw failure("CONFLICT_READ_UNAVAILABLE");
        }
    }

    private Document safeRead(MongoCollection<Document> collection, String id) {
        try { return collection.find(eq("_id", id)).first(); }
        catch (MongoException exception) { throw failure("READ_UNAVAILABLE"); }
    }

    private static Bson window(Observation observation, Instant start) {
        return and(eq("scope", scopeId(observation.scope())), eq("merchant", observation.merchant()),
                gte("eventTime", Date.from(start)), lt("eventTime", Date.from(observation.eventTime())));
    }

    private static boolean matchesSeal(Document source, CoverageSeal seal) {
        return source != null && !invalidated(source) && Objects.equals(source.get("sealedSequence"), seal.throughSequence())
                && Objects.equals(source.getDate("watermark"), Date.from(seal.watermark()))
                && Objects.equals(source.getDate("coverageStart"), Date.from(seal.coverageStart()));
    }

    private <T> T transaction(Function<ClientSession, T> body) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TRANSACTION);
                T result;
                try { result = body.apply(session); }
                catch (RuntimeException exception) {
                    abort(session);
                    if (exception instanceof MongoException mongo && retryable(mongo) && attempt + 1 < ATTEMPTS) { continue; }
                    throw exception;
                }
                try {
                    commitWithUnknownResultRetry(session);
                    return result;
                } catch (MongoException exception) {
                    if (exception.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) { throw exception; }
                    abort(session);
                    if (retryable(exception) && attempt + 1 < ATTEMPTS) { continue; }
                    throw exception;
                }
            }
        }
        throw failure("RETRY_EXHAUSTED");
    }

    static void commitWithUnknownResultRetry(ClientSession session) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try { session.commitTransaction(); return; }
            catch (MongoException exception) {
                if (!exception.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) || attempt + 1 == ATTEMPTS) {
                    throw exception;
                }
            }
        }
    }

    private static void abort(ClientSession session) {
        try { if (session.hasActiveTransaction()) { session.abortTransaction(); } }
        catch (MongoException ignored) { /* The failed operation is reconciled or reported unavailable, never as success. */ }
    }

    private static boolean retryable(MongoException exception) {
        return exception.getCode() == 11000 || exception.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
    }

    private static void authorize(Runnable check) {
        try { check.run(); }
        catch (RuntimeException exception) { throw failure("NOT_ADMITTED"); }
    }

    private static String eventId(Observation observation) {
        return observation.scope().institution() + ':' + observation.scope().source() + ':' + observation.sourceEvent();
    }
    private static String scopeId(Scope scope) {
        return scope.issuer() + ':' + scope.institution() + ':' + scope.source() + ':' + scope.epoch() + ':' + scope.identityVersion();
    }
    private static String inputCommitment(Observation observation) {
        return hash("merchant-observation/v1", observation.scope().issuer(), observation.scope().institution(), observation.scope().source(),
                observation.scope().epoch(), observation.scope().identityVersion(), observation.sourceEvent(), observation.transaction(),
                observation.occurrence(), observation.merchant(), observation.sequence(), observation.eventTime(), observation.sourceObservedAt(),
                observation.payloadCommitment());
    }
    private static String snapshotCommitment(Observation observation, Snapshot snapshot) {
        return hash("merchant-snapshot/v1", inputCommitment(observation), snapshot.windowStart(), snapshot.asOf(),
                snapshot.availability(), snapshot.priorCount(), snapshot.reason());
    }
    private static String hash(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String part = value == null ? null : value.toString();
            canonical.append(part == null ? -1 : part.getBytes(StandardCharsets.UTF_8).length).append(':');
            if (part != null) { canonical.append(part); }
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw failure("DIGEST_UNAVAILABLE"); }
    }
    private Instant now() { return Instant.ofEpochMilli(clock.millis()); }
    private static Instant instant(Document row, String field) { return row.getDate(field).toInstant(); }
    private static boolean invalidated(Document source) { return Boolean.TRUE.equals(source.getBoolean("invalidated")); }
    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
    private static IllegalStateException failure(String code) { return new IllegalStateException("MERCHANT_STORE_" + code); }
    private static MongoException race() {
        MongoException exception = new MongoException(112, "MERCHANT_STORE_WRITE_RACE");
        exception.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return exception;
    }
    private enum Capacity { NONE, PENDING, STORAGE }
    private record StageDecision(StageResult result, Capacity capacity) { }
}
