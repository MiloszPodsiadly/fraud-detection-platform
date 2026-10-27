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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

/** Inert until a trusted admission boundary and delivery protocol are wired. */
final class MongoDeviceObservationStore implements DeviceObservationStore {
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final Duration LATE_AND_REPAIR = Duration.ofDays(1).plusMinutes(5);
    private static final Duration RECEIPT_RETENTION = Duration.ofDays(45);
    private static final Duration MAX_FUTURE_EVENT_SKEW = Duration.ofMinutes(2);
    private static final Duration REVOCATION_TOMBSTONE_RETENTION = RECEIPT_RETENTION.plus(MAX_FUTURE_EVENT_SKEW);
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private static final int MAX_COMMIT_ACK_ATTEMPTS = 3;
    private static final int MAX_SEAL_BATCH = 1_000;
    private static final int MAX_PENDING_CANDIDATES = 10_000;
    private static final int MAX_DEVICE_ROWS = 10_000;
    private static final int MAX_CUSTOMER_ROWS = 25_000;
    private static final TransactionOptions TRANSACTION_OPTIONS = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build();
    private static final List<IndexSpec> CANDIDATE_INDEXES = List.of(
            index("candidate_source_event_unique", true, false, "institution", "source", "sourceEvent"),
            index("candidate_transaction_unique", true, false, "institution", "source", "transaction"),
            index("candidate_sequence_unique", true, false, "institution", "source", "sequence"),
            index("candidate_customer_event_status_sequence", false, false,
                    "institution", "source", "customer", "eventTime", "status", "sequence"),
            index("candidate_status_event_source_event", false, false,
                    "institution", "source", "status", "eventTime", "sourceEvent"),
            index("candidate_device_status", false, false, "institution", "source", "keyVersion", "device", "status"),
            index("candidate_terminal_expiry_ttl", false, true, "terminalExpireAt"));
    private static final List<IndexSpec> OBSERVATION_INDEXES = List.of(
            index("observation_device_window", false, false,
                    "institution", "source", "keyVersion", "device", "eventTime", "sourceEvent"),
            index("observation_customer_window", false, false,
                    "institution", "source", "keyVersion", "customer", "eventTime", "sourceEvent"),
            index("observation_expiry_ttl", false, true, "expireAt"));
    private static final List<IndexSpec> OCCURRENCE_INDEXES = List.of(
            index("occurrence_transaction_unique", true, false, "institution", "source", "transaction"),
            index("occurrence_device", false, false, "institution", "source", "keyVersion", "device"),
            index("occurrence_expiry_ttl", false, true, "expireAt"));
    private static final List<IndexSpec> TOMBSTONE_INDEXES = List.of(
            index("tombstone_expiry_ttl", false, true, "expireAt"));

    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<Document> candidates;
    private final MongoCollection<Document> observations;
    private final MongoCollection<Document> occurrences;
    private final MongoCollection<Document> coverage;
    private final MongoCollection<Document> fences;
    private final MongoCollection<Document> revocationTombstones;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    MongoDeviceObservationStore(MongoClient client, String databaseName, Clock clock) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (databaseName == null || !databaseName.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("DEVICE_STORE_DATABASE_NAME");
        }
        database = client.getDatabase(databaseName);
        candidates = database.getCollection("device_observation_candidates");
        observations = database.getCollection("device_observations");
        occurrences = database.getCollection("device_occurrences");
        coverage = database.getCollection("device_source_coverage");
        fences = database.getCollection("device_scope_fences");
        revocationTombstones = database.getCollection("device_operational_revocation_tombstones");
        try {
            ensureIndexes();
        } catch (MongoException exception) {
            throw unavailable("INDEXES", exception);
        }
    }

    private void ensureIndexes() {
        Set<String> existingCollections = new HashSet<>(database.listCollectionNames().into(new ArrayList<>()));
        validateExistingSchema(existingCollections);
        createIndexes(candidates, CANDIDATE_INDEXES);
        createIndexes(observations, OBSERVATION_INDEXES);
        createIndexes(occurrences, OCCURRENCE_INDEXES);
        createIndexes(revocationTombstones, TOMBSTONE_INDEXES);
    }

    private void validateExistingSchema(Set<String> existingCollections) {
        validateCollection(existingCollections, "device_observation_candidates", candidates, CANDIDATE_INDEXES, "expireAt");
        validateCollection(existingCollections, "device_observations", observations, OBSERVATION_INDEXES);
        validateCollection(existingCollections, "device_occurrences", occurrences, OCCURRENCE_INDEXES);
        validateCollection(existingCollections, "device_source_coverage", coverage, List.of(), "pendingCounterVersion");
        validateCollection(existingCollections, "device_scope_fences", fences, List.of());
        if (existingCollections.contains("device_erasure_tombstones")) {
            throw terminal("SCHEMA_INCOMPATIBLE");
        }
        validateCollection(existingCollections, "device_operational_revocation_tombstones", revocationTombstones,
                TOMBSTONE_INDEXES);
    }

    private static void createIndexes(MongoCollection<Document> collection, List<IndexSpec> indexes) {
        for (IndexSpec index : indexes) {
            collection.createIndex(index.keys(), index.options());
        }
    }

    private static void validateCollection(Set<String> existingCollections, String collectionName,
                                           MongoCollection<Document> collection, List<IndexSpec> expectedIndexes,
                                           String... forbiddenFields) {
        if (!existingCollections.contains(collectionName)) {
            return;
        }
        List<Document> actualIndexes = collection.listIndexes().into(new ArrayList<>());
        Set<String> expectedNames = new HashSet<>();
        expectedNames.add("_id_");
        for (IndexSpec expected : expectedIndexes) {
            expectedNames.add(expected.name());
        }
        Set<String> actualNames = new HashSet<>();
        for (Document actual : actualIndexes) {
            String name = actual.getString("name");
            if (name == null || !actualNames.add(name)) {
                throw terminal("SCHEMA_INCOMPATIBLE");
            }
        }
        if (!actualNames.equals(expectedNames)) {
            throw terminal("SCHEMA_INCOMPATIBLE");
        }
        for (IndexSpec expected : expectedIndexes) {
            Document actual = actualIndexes.stream()
                    .filter(index -> expected.name().equals(index.getString("name")))
                    .findFirst()
                    .orElseThrow(() -> terminal("SCHEMA_INCOMPATIBLE"));
            if (!expected.matches(actual)) {
                throw terminal("SCHEMA_INCOMPATIBLE");
            }
        }
        for (String forbiddenField : forbiddenFields) {
            if (collection.find(exists(forbiddenField, true)).limit(1).first() != null) {
                throw terminal("SCHEMA_INCOMPATIBLE");
            }
        }
    }

    @Override
    public StageResult stage(Observation input) {
        java.util.Objects.requireNonNull(input, "input");
        String id = eventId(input.institutionToken(), input.sourceCode(), input.sourceEventKey());
        try {
            Instant now = clock.instant();
            if (input.eventTime().isAfter(now.plus(MAX_FUTURE_EVENT_SKEW))
                    || input.sourceObservedAt().isAfter(now.plus(MAX_FUTURE_EVENT_SKEW))) {
                throw terminal("FUTURE_EVENT");
            }
            if (hasActiveOperationalRevocation(revocationId(input), now)) {
                throw terminal("DEVICE_OPERATIONALLY_REVOKED");
            }
            if (!input.eventTime().isAfter(now.minus(RECEIPT_RETENTION))) {
                throw terminal("REPLAY_HORIZON_EXCEEDED");
            }
            Document existing = candidates.find(eq("_id", id)).first();
            if (existing != null) {
                if (terminalCandidateExpired(existing, now)) {
                    throw terminal("CANDIDATE_EXPIRED");
                }
                return duplicateOrConflict(existing, input);
            }
            Document existingOccurrence = occurrences.find(eq("_id", id)).first();
            if (existingOccurrence != null) {
                if (occurrenceExpired(existingOccurrence, now)) {
                    throw terminal("OCCURRENCE_EXPIRED");
                }
                return duplicateOccurrenceOrConflict(existingOccurrence, input);
            }
            StageOutcome outcome = transaction(session -> {
                String scope = scopeId(input.institutionToken(), input.sourceCode());
                Document progress = coverage.find(session, eq("_id", scope)).first();
                if (progress != null && progress.getDate("watermark") != null
                        && !input.eventTime().isAfter(progress.getDate("watermark").toInstant())) {
                    invalidateSource(session, scope);
                    return StageOutcome.rejected(StageRejection.POST_SEAL);
                }
                if (hasActiveOperationalRevocation(session, revocationId(input), clock.instant())) {
                    throw terminal("DEVICE_OPERATIONALLY_REVOKED");
                }
                if (progress != null && progress.getInteger("pendingCount", 0) >= MAX_PENDING_CANDIDATES) {
                    invalidateSource(session, scope);
                    return StageOutcome.rejected(StageRejection.PENDING_CAPACITY);
                }
                coverage.updateOne(session, eq("_id", scope),
                        combine(inc("revision", 1L), inc("pendingCount", 1), setOnInsert("sealedSequence", 0L)),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
                candidates.insertOne(session, candidate(input));
                return StageOutcome.created();
            });
            if (outcome.rejection() == StageRejection.POST_SEAL) {
                throw terminal("POST_SEAL_EVENT");
            }
            if (outcome.rejection() == StageRejection.PENDING_CAPACITY) {
                throw terminal("PENDING_CAPACITY_EXCEEDED");
            }
            return outcome.result();
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
        if (seal.watermark().isAfter(clock.instant().plus(MAX_FUTURE_EVENT_SKEW))) {
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
            Document durable = safeRead(coverage, scopeId(seal.institutionToken(), seal.sourceCode()));
            if (matchesDurableSeal(durable, seal)) {
                return;
            }
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
                Document persisted = occurrences.find(eq("_id", id)).first();
                if (persisted != null && !occurrenceExpired(persisted, clock.instant())) {
                    return existing(id);
                }
                throw terminal("CANDIDATE_MISSING");
            }
            if (terminalCandidateExpired(candidate, clock.instant())) {
                throw terminal("CANDIDATE_EXPIRED");
            }
            if (hasActiveOperationalRevocation(revocationId(candidate), clock.instant())) {
                throw terminal("DEVICE_OPERATIONALLY_REVOKED");
            }
            if ("COMMITTED".equals(candidate.getString("status"))) {
                return existing(id);
            }
            if ("QUARANTINED".equals(candidate.getString("status"))) {
                return quarantined();
            }
            return transaction(session -> commitInside(session, id));
        } catch (CapacityExceeded exception) {
            return quarantineCapacity(id);
        } catch (MongoException exception) {
            Document persisted = safeRead(occurrences, id);
            if (persisted != null) {
                return existing(id);
            }
            Document durableCandidate = safeRead(candidates, id);
            if (durableCandidate != null && "QUARANTINED".equals(durableCandidate.getString("status"))) {
                return quarantined();
            }
            throw unavailable("COMMIT", exception);
        }
    }

    private CommitResult commitInside(ClientSession session, String id) {
        Document candidate = candidates.find(session, eq("_id", id)).first();
        if (candidate == null) {
            throw terminal("CANDIDATE_MISSING");
        }
        if (terminalCandidateExpired(candidate, clock.instant())) {
            throw terminal("CANDIDATE_EXPIRED");
        }
        if ("COMMITTED".equals(candidate.getString("status"))) {
            return existing(session, id);
        }
        if ("QUARANTINED".equals(candidate.getString("status"))) {
            return quarantined();
        }
        if (!"STAGED".equals(candidate.getString("status"))) {
            throw terminal("CANDIDATE_STATE");
        }
        if (hasActiveOperationalRevocation(session, revocationId(candidate), clock.instant())) {
            throw terminal("DEVICE_OPERATIONALLY_REVOKED");
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
        Bson earlierTimeOrSequence = or(lt("eventTime", Date.from(time)),
                and(eq("eventTime", Date.from(time)), lt("sequence", candidate.getLong("sequence"))));
        Bson earlier = and(scope, eq("status", "STAGED"), earlierTimeOrSequence,
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
            Document sameTimePredecessor = candidates.find(session, and(scope,
                            eq("customer", customer), eq("eventTime", Date.from(time)),
                            lt("sequence", candidate.getLong("sequence")), eq("status", "COMMITTED")))
                    .sort(com.mongodb.client.model.Sorts.descending("sequence"))
                    .first();
            boolean ambiguousCurrentTie = sameTimePredecessor != null
                    && !device.equals(sameTimePredecessor.getString("device"));
            if (previousDevice != null && !previousDevice.equals(device)) {
                transitions++;
            }
            ambiguousSwitchOrder = ambiguousSwitchOrder || ambiguousCurrentTie;
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
                .append("sourceEvent", candidate.getString("sourceEvent"))
                .append("sequence", candidate.getLong("sequence"))
                .append("customer", customer).append("device", device).append("keyVersion", version)
                .append("eventTime", Date.from(time))
                .append("payloadCommitment", candidate.getString("payloadCommitment"))
                .append("evidenceJson", mapper.writeValueAsString(evidence))
                .append("evidenceCommitment", commitment(evidence)).append("expireAt", expiry));
        transitionOutOfPending(session, candidate, "COMMITTED", false);
        return new CommitResult(CommitStatus.COMMITTED, evidence);
    }

    @Override
    public void revokeDeviceFromOperationalHistory(String institutionToken, String sourceCode, int keyVersion,
                                                    String deviceToken) {
        requireToken(institutionToken);
        requireCode(sourceCode);
        requireToken(deviceToken);
        if (keyVersion < 1 || keyVersion > 65_535) {
            throw terminal("KEY_VERSION");
        }
        String id = scopeId(institutionToken, sourceCode) + ':' + keyVersion + ':' + deviceToken;
        String sourceScope = scopeId(institutionToken, sourceCode);
        Bson scopedDevice = and(eq("institution", institutionToken), eq("source", sourceCode),
                eq("keyVersion", keyVersion), eq("device", deviceToken));
        try {
            transaction(session -> {
                revocationTombstones.updateOne(session, eq("_id", id),
                        set("expireAt", Date.from(clock.instant().plus(REVOCATION_TOMBSTONE_RETENTION))),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));

                Bson stagedDevice = and(scopedDevice, eq("status", "STAGED"));
                long stagedBefore = candidates.countDocuments(session, stagedDevice);
                long quarantined = candidates.updateMany(session, stagedDevice,
                        combine(set("status", "QUARANTINED"), set("terminalReason", "OPERATIONAL_REVOCATION_REQUESTED"),
                                set("terminalExpireAt", Date.from(clock.instant().plus(RECEIPT_RETENTION))))).getModifiedCount();
                if (quarantined != stagedBefore) {
                    throw retryable("OPERATIONAL_REVOCATION_CANDIDATE_RACE");
                }
                observations.deleteMany(session, scopedDevice);
                int remainingPending = Math.toIntExact(candidates.countDocuments(session,
                        and(eq("institution", institutionToken), eq("source", sourceCode), eq("status", "STAGED"))));
                var progress = coverage.updateOne(session, eq("_id", sourceScope),
                        combine(set("invalidated", true), set("pendingCount", remainingPending), inc("revision", 1L),
                                setOnInsert("sealedSequence", 0L)),
                        new com.mongodb.client.model.UpdateOptions().upsert(true));
                if (progress.getModifiedCount() != 1 && progress.getUpsertedId() == null) {
                    throw terminal("SOURCE_COVERAGE_MISSING");
                }
                return null;
            });
        } catch (MongoException exception) {
            throw unavailable("OPERATIONAL_REVOCATION", exception);
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
                .append("payloadCommitment", input.payloadCommitment()).append("status", "STAGED");
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

    private StageResult duplicateOccurrenceOrConflict(Document existing, Observation input) {
        DeviceRiskEvidence evidence = decode(existing);
        if (!Objects.equals(existing.getString("institution"), input.institutionToken())
                || !Objects.equals(existing.getString("source"), input.sourceCode())
                || existing.containsKey("sourceEvent")
                    && !Objects.equals(existing.getString("sourceEvent"), input.sourceEventKey())
                || !Objects.equals(existing.getString("transaction"), input.transactionKey())
                || !Objects.equals(existing.getString("payloadCommitment"), input.payloadCommitment())
                || existing.containsKey("sequence")
                    && !Objects.equals(existing.getLong("sequence"), input.sourceSequence())
                || existing.containsKey("keyVersion")
                    && !Objects.equals(existing.getInteger("keyVersion"), input.keyVersion())
                || !Objects.equals(evidence.institutionToken(), input.institutionToken())
                || !Objects.equals(evidence.occurrenceKey(), input.transactionKey())
                || !Objects.equals(evidence.source().authorityCode(), input.sourceCode())
                || !Objects.equals(evidence.source().sourceEventKey(), input.sourceEventKey())
                || !Objects.equals(evidence.customerToken(), input.customerToken())
                || !Objects.equals(evidence.deviceToken(), input.deviceToken())
                || evidence.keyVersion() != input.keyVersion()
                || !evidence.eventTime().equals(input.eventTime())
                || !evidence.source().observedAt().equals(input.sourceObservedAt())) {
            throw terminal("IDENTITY_CONFLICT");
        }
        return StageResult.DUPLICATE;
    }

    private boolean hasActiveOperationalRevocation(String id, Instant now) {
        Document tombstone = revocationTombstones.find(eq("_id", id)).first();
        return tombstone != null && !isExpired(tombstone, "expireAt", now);
    }

    private boolean hasActiveOperationalRevocation(ClientSession session, String id, Instant now) {
        Document tombstone = session == null
                ? revocationTombstones.find(eq("_id", id)).first()
                : revocationTombstones.find(session, eq("_id", id)).first();
        return tombstone != null && !isExpired(tombstone, "expireAt", now);
    }

    private static boolean terminalCandidateExpired(Document candidate, Instant now) {
        return !"STAGED".equals(candidate.getString("status"))
                && (!candidate.containsKey("terminalExpireAt")
                    || isExpired(candidate, "terminalExpireAt", now));
    }

    private static boolean occurrenceExpired(Document occurrence, Instant now) {
        return !occurrence.containsKey("expireAt") || isExpired(occurrence, "expireAt", now);
    }

    private static boolean isExpired(Document document, String field, Instant now) {
        Date expiry = document.getDate(field);
        return expiry != null && !expiry.toInstant().isAfter(now);
    }

    private CommitResult existing(String id) {
        Document persisted = occurrences.find(eq("_id", id)).first();
        if (persisted == null) {
            throw terminal("OCCURRENCE_MISSING");
        }
        Document candidate = candidates.find(eq("_id", id)).first();
        return existingResult(null, candidate, persisted);
    }

    private CommitResult existing(ClientSession session, String id) {
        Document persisted = occurrences.find(session, eq("_id", id)).first();
        if (persisted == null) {
            throw terminal("OCCURRENCE_MISSING");
        }
        Document candidate = candidates.find(session, eq("_id", id)).first();
        return existingResult(session, candidate, persisted);
    }

    private CommitResult existingResult(ClientSession session, Document candidate, Document persisted) {
        Instant now = clock.instant();
        if (occurrenceExpired(persisted, now)) {
            throw terminal("OCCURRENCE_EXPIRED");
        }
        if (candidate != null && terminalCandidateExpired(candidate, now)) {
            throw terminal("CANDIDATE_EXPIRED");
        }
        DeviceRiskEvidence evidence = decode(persisted);
        if (!Objects.equals(persisted.getString("institution"), evidence.institutionToken())
                || !Objects.equals(persisted.getString("source"), evidence.source().authorityCode())
                || !Objects.equals(persisted.getString("transaction"), evidence.occurrenceKey())) {
            throw terminal("OCCURRENCE_IDENTITY_CONFLICT");
        }
        if (evidence.deviceToken() != null && hasActiveOperationalRevocation(session,
                scopeId(evidence.institutionToken(), evidence.source().authorityCode()) + ':'
                        + evidence.keyVersion() + ':' + evidence.deviceToken(), now)) {
            throw terminal("DEVICE_OPERATIONALLY_REVOKED");
        }
        if (candidate != null && (!"COMMITTED".equals(candidate.getString("status"))
                || !Objects.equals(candidate.getString("institution"), persisted.getString("institution"))
                || !Objects.equals(candidate.getString("source"), persisted.getString("source"))
                || !Objects.equals(candidate.getString("transaction"), persisted.getString("transaction"))
                || !Objects.equals(candidate.getString("payloadCommitment"), persisted.getString("payloadCommitment"))
                || !Objects.equals(candidate.getString("sourceEvent"), evidence.source().sourceEventKey())
                || candidate.getInteger("keyVersion") != evidence.keyVersion()
                || !candidate.getDate("eventTime").toInstant().equals(evidence.eventTime()))) {
            throw terminal("OCCURRENCE_IDENTITY_CONFLICT");
        }
        return new CommitResult(CommitStatus.EXISTING, evidence);
    }

    private CommitResult quarantineCapacity(String id) {
        try {
            return transaction(session -> {
                Document candidate = candidates.find(session, eq("_id", id)).first();
                if (candidate == null) {
                    throw terminal("CANDIDATE_MISSING");
                }
                if ("COMMITTED".equals(candidate.getString("status"))) {
                    return existing(session, id);
                }
                if ("QUARANTINED".equals(candidate.getString("status"))) {
                    return quarantined();
                }
                if (!"STAGED".equals(candidate.getString("status"))) {
                    throw terminal("CANDIDATE_STATE");
                }
                transitionOutOfPending(session, candidate, "QUARANTINED", true);
                return quarantined();
            });
        } catch (MongoException exception) {
            Document durable = safeRead(candidates, id);
            if (durable != null && "QUARANTINED".equals(durable.getString("status"))) {
                return quarantined();
            }
            if (durable != null && "COMMITTED".equals(durable.getString("status"))) {
                return existing(id);
            }
            throw unavailable("CAPACITY_QUARANTINE", exception);
        }
    }

    private void transitionOutOfPending(ClientSession session, Document candidate, String nextState,
                                        boolean invalidateSource) {
        Bson candidateFilter = and(eq("_id", candidate.getString("_id")), eq("status", "STAGED"));
        Bson candidateUpdate = nextState.equals("QUARANTINED")
                ? combine(set("status", nextState), set("terminalReason", "CAPACITY_EXCEEDED"),
                    set("terminalExpireAt", Date.from(clock.instant().plus(RECEIPT_RETENTION))))
                : combine(set("status", nextState),
                    set("terminalExpireAt", Date.from(clock.instant().plus(RECEIPT_RETENTION))));
        if (candidates.updateOne(session, candidateFilter, candidateUpdate).getModifiedCount() != 1) {
            throw retryable("CANDIDATE_STATE_RACE");
        }

        Bson coverageFilter = and(eq("_id", scopeId(candidate)), gt("pendingCount", 0));
        Bson coverageUpdate = invalidateSource
                ? combine(set("invalidated", true), inc("pendingCount", -1),
                        inc("revision", 1L))
                : combine(inc("pendingCount", -1), inc("revision", 1L));
        if (coverage.updateOne(session, coverageFilter, coverageUpdate).getModifiedCount() != 1) {
            throw terminal("PENDING_COUNT_INVARIANT");
        }
    }

    private static CommitResult quarantined() {
        return new CommitResult(CommitStatus.QUARANTINED, null);
    }

    private static boolean matchesDurableSeal(Document progress, CoverageSeal seal) {
        return progress != null
                && progress.getLong("sealedSequence") == seal.throughSequence()
                && Objects.equals(dateInstant(progress, "watermark"), seal.watermark())
                && Objects.equals(progress.getInteger("keyVersion"), seal.keyVersion())
                && dateInstant(progress, "coverageStart") != null
                && !dateInstant(progress, "coverageStart").isBefore(seal.coverageStart());
    }

    private void invalidateSource(ClientSession session, String scope) {
        long changed = coverage.updateOne(session, eq("_id", scope),
                combine(set("invalidated", true), inc("revision", 1L))).getModifiedCount();
        if (changed != 1) {
            throw terminal("SOURCE_COVERAGE_MISSING");
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
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TRANSACTION_OPTIONS);
                T result;
                try {
                    result = body.apply(session);
                } catch (RuntimeException exception) {
                    if (session.hasActiveTransaction()) {
                        session.abortTransaction();
                    }
                    if (exception instanceof MongoException mongo
                            && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                            && attempt + 1 < MAX_TRANSACTION_ATTEMPTS) {
                        continue;
                    }
                    throw exception;
                }
                try {
                    commitWithUnknownResultRetry(session);
                    return result;
                } catch (RuntimeException exception) {
                    if (exception instanceof MongoException mongo
                            && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                            && attempt + 1 < MAX_TRANSACTION_ATTEMPTS) {
                        if (session.hasActiveTransaction()) {
                            session.abortTransaction();
                        }
                        continue;
                    }
                    boolean outcomeUnknown = exception instanceof MongoException mongo
                            && mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
                    if (!outcomeUnknown && session.hasActiveTransaction()) {
                        session.abortTransaction();
                    }
                    throw exception;
                }
            }
        }
        throw retryable("RETRY_EXHAUSTED");
    }

    static void commitWithUnknownResultRetry(ClientSession session) {
        for (int attempt = 0; attempt < MAX_COMMIT_ACK_ATTEMPTS; attempt++) {
            try {
                session.commitTransaction();
                return;
            } catch (MongoException exception) {
                if (!exception.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                        || attempt + 1 == MAX_COMMIT_ACK_ATTEMPTS) {
                    throw exception;
                }
            }
        }
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

    private static String revocationId(Observation input) {
        return scopeId(input.institutionToken(), input.sourceCode()) + ':' + input.keyVersion() + ':' + input.deviceToken();
    }

    private static String revocationId(Document candidate) {
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

    private enum StageRejection { NONE, POST_SEAL, PENDING_CAPACITY }

    private record StageOutcome(StageResult result, StageRejection rejection) {
        private StageOutcome {
            if ((rejection == StageRejection.NONE) != (result != null)) {
                throw new IllegalArgumentException("DEVICE_STORE_STAGE_OUTCOME");
            }
        }

        private static StageOutcome created() {
            return new StageOutcome(StageResult.CREATED, StageRejection.NONE);
        }

        private static StageOutcome rejected(StageRejection rejection) {
            return new StageOutcome(null, rejection);
        }
    }

    private static IndexSpec index(String name, boolean unique, boolean ttl, String... fields) {
        Document keys = new Document();
        for (String field : fields) {
            keys.append(field, 1);
        }
        return new IndexSpec(name, keys, unique, ttl);
    }

    private record IndexSpec(String name, Document keys, boolean unique, boolean ttl) {
        private IndexOptions options() {
            IndexOptions options = new IndexOptions().name(name);
            if (unique) {
                options.unique(true);
            }
            if (ttl) {
                options.expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS);
            }
            return options;
        }

        private boolean matches(Document actual) {
            if (!keys.equals(actual.get("key", Document.class))
                    || unique != Boolean.TRUE.equals(actual.get("unique"))) {
                return false;
            }
            if (!unique && actual.containsKey("unique")) {
                return false;
            }
            if (!ttl) {
                return !actual.containsKey("expireAfterSeconds");
            }
            Object expiry = actual.get("expireAfterSeconds");
            return expiry instanceof Number number && number.longValue() == 0L;
        }
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
