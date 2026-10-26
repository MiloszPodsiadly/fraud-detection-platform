package com.frauddetection.enricher.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.common.events.device.DeviceRiskEvidence.CountryFact;
import com.frauddetection.common.events.device.DeviceRiskEvidence.HistoryFact;
import com.frauddetection.common.events.device.DeviceRiskEvidence.NetworkFact;
import com.frauddetection.common.events.device.DeviceRiskEvidence.Reason;
import com.frauddetection.common.events.device.DeviceRiskEvidence.Source;
import com.frauddetection.common.events.device.DeviceRiskEvidence.SourceQuality;
import com.frauddetection.common.events.device.DeviceRiskEvidence.State;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

/** Inert until a trusted admission boundary and delivery protocol are wired. */
public final class MongoDeviceObservationStore implements DeviceObservationStore {
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final Duration LATE_AND_REPAIR = Duration.ofDays(1).plusMinutes(5);
    private static final Duration RECEIPT_RETENTION = Duration.ofDays(45);
    private static final int MAX_SEAL_BATCH = 1_000;
    private static final int MAX_PENDING_CANDIDATES = 10_000;
    private static final int MAX_DEVICE_ROWS = 10_000;
    private static final int MAX_CUSTOMER_ROWS = 25_000;
    private static final TransactionOptions TRANSACTION_OPTIONS = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build();

    private final MongoClient client;
    private final MongoCollection<Document> candidates;
    private final MongoCollection<Document> observations;
    private final MongoCollection<Document> occurrences;
    private final MongoCollection<Document> coverage;
    private final MongoCollection<Document> fences;
    private final MongoCollection<Document> tombstones;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    public MongoDeviceObservationStore(MongoClient client, String databaseName, Clock clock) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (databaseName == null || !databaseName.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("DEVICE_STORE_DATABASE_NAME");
        }
        MongoDatabase database = client.getDatabase(databaseName);
        candidates = database.getCollection("device_observation_candidates");
        observations = database.getCollection("device_observations");
        occurrences = database.getCollection("device_occurrences");
        coverage = database.getCollection("device_source_coverage");
        fences = database.getCollection("device_scope_fences");
        tombstones = database.getCollection("device_erasure_tombstones");
        try {
            ensureIndexes();
        } catch (MongoException exception) {
            throw unavailable("INDEXES", exception);
        }
    }

    private void ensureIndexes() {
        candidates.createIndex(ascending("institution", "source", "sourceEvent"), new IndexOptions().unique(true));
        candidates.createIndex(ascending("institution", "source", "transaction"), new IndexOptions().unique(true));
        candidates.createIndex(ascending("institution", "source", "sequence"), new IndexOptions().unique(true));
        candidates.createIndex(ascending("institution", "source", "status", "eventTime", "sourceEvent"));
        candidates.createIndex(ascending("expireAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        observations.createIndex(ascending("institution", "source", "keyVersion", "device", "eventTime", "sourceEvent"));
        observations.createIndex(ascending("institution", "source", "keyVersion", "customer", "eventTime", "sourceEvent"));
        observations.createIndex(ascending("expireAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        occurrences.createIndex(ascending("institution", "source", "transaction"), new IndexOptions().unique(true));
        occurrences.createIndex(ascending("expireAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        tombstones.createIndex(ascending("expireAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Override
    public StageResult stage(Observation input) {
        java.util.Objects.requireNonNull(input, "input");
        String id = eventId(input.institutionToken(), input.sourceCode(), input.sourceEventKey());
        try {
            if (tombstones.find(eq("_id", tombstoneId(input))).first() != null) {
                throw terminal("ERASED_DEVICE");
            }
            Document existing = candidates.find(eq("_id", id)).first();
            if (existing != null) {
                return duplicateOrConflict(existing, input);
            }
            if (input.eventTime().isAfter(clock.instant().plusSeconds(120))
                    || input.sourceObservedAt().isAfter(clock.instant().plusSeconds(120))) {
                throw terminal("FUTURE_EVENT");
            }
            if (input.eventTime().isBefore(clock.instant().minus(RECEIPT_RETENTION))) {
                throw terminal("REPLAY_HORIZON_EXCEEDED");
            }
            return transaction(session -> {
                Document progress = coverage.find(session, eq("_id", scopeId(input.institutionToken(), input.sourceCode()))).first();
                if (progress != null && progress.getDate("watermark") != null
                        && !input.eventTime().isAfter(progress.getDate("watermark").toInstant())) {
                    throw new PostSealEvent();
                }
                if (tombstones.find(session, eq("_id", tombstoneId(input))).first() != null) {
                    throw terminal("ERASED_DEVICE");
                }
                if (progress != null && progress.getInteger("pendingCount", 0) >= MAX_PENDING_CANDIDATES) {
                    throw new PendingCapacityExceeded();
                }
                coverage.updateOne(session, eq("_id", scopeId(input.institutionToken(), input.sourceCode())),
                        combine(inc("revision", 1L), inc("pendingCount", 1), setOnInsert("sealedSequence", 0L)),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
                candidates.insertOne(session, candidate(input));
                return StageResult.CREATED;
            });
        } catch (PostSealEvent exception) {
            invalidateSource(input.institutionToken(), input.sourceCode());
            throw terminal("POST_SEAL_EVENT");
        } catch (PendingCapacityExceeded exception) {
            invalidateSource(input.institutionToken(), input.sourceCode());
            throw terminal("PENDING_CAPACITY_EXCEEDED");
        } catch (MongoException exception) {
            Document existing = safeRead(candidates, id);
            if (existing != null) {
                return duplicateOrConflict(existing, input);
            }
            try {
                Document conflictingTransaction = candidates.find(and(eq("institution", input.institutionToken()),
                        eq("source", input.sourceCode()), eq("transaction", input.transactionKey()))).first();
                Document conflictingSequence = candidates.find(and(eq("institution", input.institutionToken()),
                        eq("source", input.sourceCode()), eq("sequence", input.sourceSequence()))).first();
                if (conflictingTransaction != null || conflictingSequence != null) {
                    throw terminal("IDENTITY_CONFLICT");
                }
            } catch (MongoException readFailure) {
                throw unavailable("CONFLICT_READ", readFailure);
            }
            throw unavailable("STAGE", exception);
        }
    }

    @Override
    public void seal(CoverageSeal seal) {
        java.util.Objects.requireNonNull(seal, "seal");
        if (seal.watermark().isAfter(clock.instant().plusSeconds(120))) {
            throw terminal("FUTURE_WATERMARK");
        }
        try {
            transaction(session -> {
                String id = scopeId(seal.institutionToken(), seal.sourceCode());
                Document progress = coverage.find(session, eq("_id", id)).first();
                if (progress == null) {
                    throw terminal("NO_STAGED_SOURCE");
                }
                long previous = progress.getLong("sealedSequence");
                if (seal.throughSequence() == previous
                        && seal.watermark().equals(dateInstant(progress, "watermark"))
                        && seal.keyVersion() == progress.getInteger("keyVersion")
                        && seal.coverageStart().equals(dateInstant(progress, "coverageStart"))) {
                    return null;
                }
                if (seal.throughSequence() <= previous || seal.throughSequence() - previous > MAX_SEAL_BATCH
                        || progress.getDate("watermark") != null
                        && seal.watermark().isBefore(dateInstant(progress, "watermark"))) {
                    throw terminal("SEAL_REGRESSION");
                }
                List<Document> batch = candidates.find(session, and(eq("institution", seal.institutionToken()),
                                eq("source", seal.sourceCode()), gt("sequence", previous),
                                lte("sequence", seal.throughSequence())))
                        .sort(com.mongodb.client.model.Sorts.ascending("sequence")).into(new ArrayList<>());
                if (batch.size() != seal.throughSequence() - previous) {
                    throw terminal("SOURCE_SEQUENCE_GAP");
                }
                long expected = previous + 1;
                for (Document candidate : batch) {
                    if (candidate.getLong("sequence") != expected++
                            || candidate.getInteger("keyVersion") != seal.keyVersion()
                            || candidate.getDate("eventTime").toInstant().isAfter(seal.watermark())) {
                        throw terminal("SOURCE_SEQUENCE_OR_TIME_CONFLICT");
                    }
                }
                if (candidates.find(session, and(eq("institution", seal.institutionToken()),
                        eq("source", seal.sourceCode()), gt("sequence", seal.throughSequence()),
                        lte("eventTime", Date.from(seal.watermark())))).first() != null) {
                    throw terminal("SEAL_OMITS_EARLIER_EVENT");
                }
                Instant oldStart = dateInstant(progress, "coverageStart");
                Instant effectiveStart = oldStart == null || seal.coverageStart().isAfter(oldStart)
                        ? seal.coverageStart() : oldStart;
                Integer oldVersion = progress.getInteger("keyVersion");
                Instant continuity = oldVersion == null || oldVersion == seal.keyVersion()
                        ? dateInstant(progress, "continuousSince") : seal.watermark();
                if (continuity == null || effectiveStart.isAfter(continuity)) {
                    continuity = effectiveStart;
                }
                long changed = coverage.updateOne(session,
                        and(eq("_id", id), eq("revision", progress.getLong("revision"))),
                        combine(set("sealedSequence", seal.throughSequence()), set("watermark", Date.from(seal.watermark())),
                                set("coverageStart", Date.from(effectiveStart)), set("continuousSince", Date.from(continuity)),
                                set("keyVersion", seal.keyVersion()), inc("revision", 1L))).getModifiedCount();
                if (changed != 1) {
                    throw retryable("SEAL_RACE");
                }
                return null;
            });
        } catch (MongoException exception) {
            throw unavailable("SEAL", exception);
        }
    }

    @Override
    public CommitResult commit(String institutionToken, String sourceCode, String sourceEventKey) {
        requireToken(institutionToken);
        requireCode(sourceCode);
        requireToken(sourceEventKey);
        String id = eventId(institutionToken, sourceCode, sourceEventKey);
        try {
            Document candidate = candidates.find(eq("_id", id)).first();
            if (candidate == null) {
                throw terminal("CANDIDATE_MISSING");
            }
            if (tombstones.find(eq("_id", tombstoneId(candidate))).first() != null) {
                throw terminal("ERASED_DEVICE");
            }
            if ("COMMITTED".equals(candidate.getString("status"))) {
                return existing(id);
            }
            return transaction(session -> commitInside(session, id));
        } catch (CapacityExceeded exception) {
            quarantineCapacity(id);
            return new CommitResult(CommitStatus.QUARANTINED, null);
        } catch (MongoException exception) {
            Document persisted = safeRead(occurrences, id);
            if (persisted != null) {
                return new CommitResult(CommitStatus.EXISTING, decode(persisted));
            }
            throw unavailable("COMMIT", exception);
        }
    }

    private CommitResult commitInside(ClientSession session, String id) {
        Document candidate = candidates.find(session, eq("_id", id)).first();
        if (candidate == null) {
            throw terminal("CANDIDATE_MISSING");
        }
        if ("COMMITTED".equals(candidate.getString("status"))) {
            Document persisted = occurrences.find(session, eq("_id", id)).first();
            if (persisted == null) {
                throw terminal("OCCURRENCE_MISSING");
            }
            return new CommitResult(CommitStatus.EXISTING, decode(persisted));
        }
        if (!"STAGED".equals(candidate.getString("status"))) {
            throw terminal("CANDIDATE_STATE");
        }
        if (tombstones.find(session, eq("_id", tombstoneId(candidate))).first() != null) {
            throw terminal("ERASED_DEVICE");
        }
        Document progress = coverage.find(session, eq("_id", scopeId(candidate))).first();
        Instant time = candidate.getDate("eventTime").toInstant();
        if (progress == null || progress.getDate("watermark") == null
                || progress.getLong("sealedSequence") < candidate.getLong("sequence")
                || dateInstant(progress, "watermark").isBefore(time)) {
            return new CommitResult(CommitStatus.WAITING_FOR_SEAL, null);
        }
        Bson scope = and(eq("institution", candidate.getString("institution")),
                eq("source", candidate.getString("source")));
        Bson earlier = and(scope, eq("status", "STAGED"), lt("eventTime", Date.from(time)),
                or(eq("customer", candidate.getString("customer")), eq("device", candidate.getString("device"))));
        if (candidates.find(session, earlier).first() != null) {
            return new CommitResult(CommitStatus.WAITING_FOR_EARLIER_EVENT, null);
        }

        String institution = candidate.getString("institution");
        String source = candidate.getString("source");
        String customer = candidate.getString("customer");
        String device = candidate.getString("device");
        int version = candidate.getInteger("keyVersion");
        for (String fenceId : List.of("customer:" + institution + ':' + source + ':' + customer,
                "device:" + institution + ':' + source + ':' + version + ':' + device)) {
            fences.updateOne(session, eq("_id", fenceId), inc("revision", 1L),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
        }
        Instant windowStart = time.minus(WINDOW);
        boolean complete = !Boolean.TRUE.equals(progress.getBoolean("invalidated"))
                && version == progress.getInteger("keyVersion")
                && !dateInstant(progress, "coverageStart").isAfter(windowStart)
                && !dateInstant(progress, "continuousSince").isAfter(windowStart)
                && !clock.instant().isAfter(time.plus(LATE_AND_REPAIR));
        HistoryFact prior;
        HistoryFact other;
        HistoryFact switches;
        if (complete) {
            Bson base = and(scope, eq("keyVersion", version), gte("eventTime", Date.from(windowStart)),
                    lt("eventTime", Date.from(time)), gt("expireAt", Date.from(clock.instant())));
            List<Document> deviceRows = observations.find(session, and(base, eq("device", device)))
                    .sort(com.mongodb.client.model.Sorts.ascending("eventTime", "sourceEvent"))
                    .limit(MAX_DEVICE_ROWS + 1).into(new ArrayList<>());
            List<Document> customerRows = observations.find(session, and(base, eq("customer", customer)))
                    .sort(com.mongodb.client.model.Sorts.ascending("eventTime", "sourceEvent"))
                    .limit(MAX_CUSTOMER_ROWS + 1).into(new ArrayList<>());
            if (deviceRows.size() > MAX_DEVICE_ROWS || customerRows.size() > MAX_CUSTOMER_ROWS) {
                throw new CapacityExceeded();
            }
            Set<String> otherCustomers = new HashSet<>();
            int priorCount = 0;
            for (Document row : deviceRows) {
                if (customer.equals(row.getString("customer"))) {
                    priorCount++;
                } else {
                    otherCustomers.add(row.getString("customer"));
                }
            }
            if (otherCustomers.size() > DeviceRiskEvidence.MAX_OTHER_CUSTOMERS) {
                throw new CapacityExceeded();
            }
            int transitions = 0;
            String previousDevice = null;
            Instant previousTime = null;
            boolean ambiguousSwitchOrder = false;
            for (Document row : customerRows) {
                String observedDevice = row.getString("device");
                Instant observedTime = row.getDate("eventTime").toInstant();
                if (previousDevice != null && !previousDevice.equals(observedDevice)) {
                    if (previousTime.equals(observedTime)) {
                        ambiguousSwitchOrder = true;
                    } else {
                        transitions++;
                    }
                }
                previousDevice = observedDevice;
                previousTime = observedTime;
            }
            prior = new HistoryFact(State.AVAILABLE, Reason.NONE, priorCount);
            other = new HistoryFact(State.AVAILABLE, Reason.NONE, otherCustomers.size());
            switches = ambiguousSwitchOrder
                    ? new HistoryFact(State.UNAVAILABLE, Reason.INCOMPLETE_HISTORY, null)
                    : new HistoryFact(State.AVAILABLE, Reason.NONE, transitions);
        } else {
            prior = new HistoryFact(State.UNAVAILABLE, Reason.INCOMPLETE_HISTORY, null);
            other = new HistoryFact(State.UNAVAILABLE, Reason.INCOMPLETE_HISTORY, null);
            switches = new HistoryFact(State.UNAVAILABLE, Reason.INCOMPLETE_HISTORY, null);
        }
        DeviceRiskEvidence evidence = new DeviceRiskEvidence(DeviceRiskEvidence.VERSION,
                candidate.getString("transaction"), institution, customer, device, version,
                new Source(SourceQuality.AUTHENTICATED_INSTITUTION, source, candidate.getString("sourceEvent"),
                        candidate.getDate("sourceObservedAt").toInstant()), time, windowStart, time,
                dateInstant(progress, "watermark"), prior, other, switches,
                new CountryFact(State.UNAVAILABLE, Reason.PROVIDER_MISSING, null, null, null, null),
                new NetworkFact(State.UNAVAILABLE, Reason.PROVIDER_MISSING, null, null, null, null));
        Date expiry = Date.from(clock.instant().plus(RECEIPT_RETENTION));
        observations.insertOne(session, new Document("_id", id).append("institution", institution)
                .append("source", source).append("sourceEvent", candidate.getString("sourceEvent"))
                .append("customer", customer).append("device", device).append("keyVersion", version)
                .append("eventTime", Date.from(time)).append("expireAt", Date.from(time.plus(WINDOW).plus(LATE_AND_REPAIR))));
        occurrences.insertOne(session, new Document("_id", id).append("institution", institution)
                .append("source", source).append("transaction", candidate.getString("transaction"))
                .append("payloadCommitment", candidate.getString("payloadCommitment"))
                .append("evidenceJson", mapper.writeValueAsString(evidence))
                .append("evidenceCommitment", commitment(evidence)).append("expireAt", expiry));
        long changed = candidates.updateOne(session, and(eq("_id", id), eq("status", "STAGED")),
                set("status", "COMMITTED")).getModifiedCount();
        if (changed != 1) {
            throw retryable("CANDIDATE_RACE");
        }
        coverage.updateOne(session, eq("_id", scopeId(candidate)),
                combine(inc("pendingCount", -1), inc("revision", 1L)));
        return new CommitResult(CommitStatus.COMMITTED, evidence);
    }

    @Override
    public void eraseDevice(String institutionToken, String sourceCode, int keyVersion, String deviceToken) {
        requireToken(institutionToken);
        requireCode(sourceCode);
        requireToken(deviceToken);
        if (keyVersion < 1 || keyVersion > 65_535) {
            throw terminal("KEY_VERSION");
        }
        String id = scopeId(institutionToken, sourceCode) + ':' + keyVersion + ':' + deviceToken;
        try {
            transaction(session -> {
                tombstones.updateOne(session, eq("_id", id),
                        setOnInsert("expireAt", Date.from(clock.instant().plus(RECEIPT_RETENTION))),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
                observations.deleteMany(session, and(eq("institution", institutionToken), eq("source", sourceCode),
                        eq("keyVersion", keyVersion), eq("device", deviceToken)));
                coverage.updateOne(session, eq("_id", scopeId(institutionToken, sourceCode)),
                        combine(set("invalidated", true), inc("revision", 1L)));
                return null;
            });
        } catch (MongoException exception) {
            throw unavailable("ERASE", exception);
        }
    }

    private Document candidate(Observation input) {
        return new Document("_id", eventId(input.institutionToken(), input.sourceCode(), input.sourceEventKey()))
                .append("institution", input.institutionToken()).append("source", input.sourceCode())
                .append("sourceEvent", input.sourceEventKey()).append("transaction", input.transactionKey())
                .append("customer", input.customerToken()).append("device", input.deviceToken())
                .append("keyVersion", input.keyVersion()).append("sequence", input.sourceSequence())
                .append("eventTime", Date.from(input.eventTime()))
                .append("sourceObservedAt", Date.from(input.sourceObservedAt()))
                .append("payloadCommitment", input.payloadCommitment()).append("status", "STAGED")
                .append("expireAt", Date.from(clock.instant().plus(RECEIPT_RETENTION)));
    }

    private StageResult duplicateOrConflict(Document existing, Observation input) {
        if ("QUARANTINED".equals(existing.getString("status"))) {
            throw terminal("CANDIDATE_QUARANTINED");
        }
        if (!existing.getString("payloadCommitment").equals(input.payloadCommitment())
                || !existing.getString("transaction").equals(input.transactionKey())
                || !existing.getString("customer").equals(input.customerToken())
                || !existing.getString("device").equals(input.deviceToken())
                || existing.getInteger("keyVersion") != input.keyVersion()
                || existing.getLong("sequence") != input.sourceSequence()
                || !existing.getDate("eventTime").toInstant().equals(input.eventTime())
                || !existing.getDate("sourceObservedAt").toInstant().equals(input.sourceObservedAt())) {
            throw terminal("IDENTITY_CONFLICT");
        }
        return StageResult.DUPLICATE;
    }

    private CommitResult existing(String id) {
        Document persisted = occurrences.find(eq("_id", id)).first();
        if (persisted == null) {
            throw terminal("OCCURRENCE_MISSING");
        }
        return new CommitResult(CommitStatus.EXISTING, decode(persisted));
    }

    private void quarantineCapacity(String id) {
        try {
            transaction(session -> {
                Document candidate = candidates.find(session, eq("_id", id)).first();
                if (candidate == null) {
                    throw terminal("CANDIDATE_MISSING");
                }
                coverage.updateOne(session, eq("_id", scopeId(candidate)),
                        combine(set("invalidated", true), inc("pendingCount", -1), inc("revision", 1L)));
                candidates.updateOne(session, and(eq("_id", id), eq("status", "STAGED")),
                        set("status", "QUARANTINED"));
                return null;
            });
        } catch (MongoException exception) {
            throw unavailable("CAPACITY_QUARANTINE", exception);
        }
    }

    private void invalidateSource(String institution, String source) {
        try {
            transaction(session -> {
                coverage.updateOne(session, eq("_id", scopeId(institution, source)),
                        combine(set("invalidated", true), inc("revision", 1L)));
                return null;
            });
        } catch (MongoException exception) {
            throw unavailable("SOURCE_INVALIDATION", exception);
        }
    }

    private DeviceRiskEvidence decode(Document persisted) {
        try {
            DeviceRiskEvidence evidence = mapper.readValue(persisted.getString("evidenceJson"), DeviceRiskEvidence.class);
            if (!commitment(evidence).equals(persisted.getString("evidenceCommitment"))) {
                throw new IllegalStateException("DEVICE_STORE_CORRUPT_SNAPSHOT");
            }
            return evidence;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("DEVICE_STORE_CORRUPT_SNAPSHOT", exception);
        }
    }

    private static String commitment(DeviceRiskEvidence evidence) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence.canonicalBytes()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("DEVICE_STORE_DIGEST_UNAVAILABLE", exception);
        }
    }

    private Document safeRead(MongoCollection<Document> collection, String id) {
        try {
            return collection.find(eq("_id", id)).first();
        } catch (MongoException exception) {
            throw unavailable("UNRESOLVED_COMMIT", exception);
        }
    }

    private <T> T transaction(Function<ClientSession, T> body) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TRANSACTION_OPTIONS);
                try {
                    T result = body.apply(session);
                    session.commitTransaction();
                    return result;
                } catch (RuntimeException exception) {
                    if (session.hasActiveTransaction()) {
                        session.abortTransaction();
                    }
                    if (exception instanceof MongoException mongo
                            && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                            && attempt < 2) {
                        continue;
                    }
                    throw exception;
                }
            }
        }
        throw retryable("RETRY_EXHAUSTED");
    }

    private static String eventId(String institution, String source, String event) {
        return scopeId(institution, source) + ':' + event;
    }

    private static String scopeId(Document candidate) {
        return scopeId(candidate.getString("institution"), candidate.getString("source"));
    }

    private static String scopeId(String institution, String source) {
        return institution + ':' + source;
    }

    private static String tombstoneId(Observation input) {
        return scopeId(input.institutionToken(), input.sourceCode()) + ':' + input.keyVersion() + ':' + input.deviceToken();
    }

    private static String tombstoneId(Document candidate) {
        return scopeId(candidate) + ':' + candidate.getInteger("keyVersion") + ':' + candidate.getString("device");
    }

    private static Instant dateInstant(Document document, String key) {
        Date value = document.getDate(key);
        return value == null ? null : value.toInstant();
    }

    private static void requireToken(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw terminal("TOKEN");
        }
    }

    private static void requireCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw terminal("SOURCE");
        }
    }

    private static final class CapacityExceeded extends RuntimeException {
    }

    private static final class PostSealEvent extends RuntimeException {
    }

    private static final class PendingCapacityExceeded extends RuntimeException {
    }

    private static IllegalStateException terminal(String code) {
        return new IllegalStateException("DEVICE_STORE_" + code);
    }

    private static IllegalStateException retryable(String code) {
        return new IllegalStateException("DEVICE_STORE_RETRY_" + code);
    }

    private static IllegalStateException unavailable(String operation, MongoException cause) {
        return new IllegalStateException("DEVICE_STORE_UNAVAILABLE_" + operation, cause);
    }
}
