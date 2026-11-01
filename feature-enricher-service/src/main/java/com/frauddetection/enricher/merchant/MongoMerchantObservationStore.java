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
import com.mongodb.client.model.ReplaceOptions;
import com.frauddetection.enricher.merchant.MerchantObservationDocumentCodec.MembershipAnchor;
import com.frauddetection.enricher.merchant.MerchantObservationDocumentCodec.MembershipCheckpoint;
import com.frauddetection.enricher.merchant.MerchantObservationDocumentCodec.MembershipTip;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Updates.*;
import static com.frauddetection.enricher.merchant.MerchantObservationDocumentCodec.*;

/** Package-internal and not a Spring component. There is no production verifier, factory, connection or importer. */
final class MongoMerchantObservationStore implements MerchantObservationStore {
    static final int ATTEMPTS = 3;
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY)
            .readPreference(ReadPreference.primary()).build();
    private final MongoClient client;
    private final MongoCollection<Document> candidates;
    private final MongoCollection<Document> history;
    private final MongoCollection<Document> manifest;
    private final MongoCollection<Document> coverage;
    private final MongoCollection<Document> membership;
    private final MongoCollection<Document> membershipTips;
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
        policyCommitment = MerchantCommitment.sha256("MERCHANT_STORE_DIGEST_UNAVAILABLE", "merchant-store-policy/v3",
                limits.window(), limits.historyRetention(), limits.receiptRetention(), limits.replayHorizon(),
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
        manifest = database.getCollection(MongoMerchantObservationSchema.MANIFEST);
        coverage = database.getCollection(MongoMerchantObservationSchema.COVERAGE);
        membership = database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP);
        membershipTips = database.getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS);
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
                Document existing = candidates.find(session, and(exactScope(observation.scope()),
                                eq("sourceEvent", observation.sourceEvent())))
                        .hintString("merchant_event_unique").limit(1).first();
                Document existingManifest = manifest.find(session, and(exactScope(observation.scope()),
                                eq("sourceEvent", observation.sourceEvent())))
                        .hintString("merchant_manifest_event_unique").limit(1).first();
                Document existingMembership = membership.find(session, eq("_id", eventId(observation))).first();
                if (existing != null) {
                    requireSource(source, observation.scope());
                    if (!matchesManifest(existing, existingManifest)) {
                        invalidate(session, observation.scope());
                        return new StageDecision(StageResult.QUARANTINED, Capacity.NONE);
                    }
                    return new StageDecision(existingStage(existing, observation), Capacity.NONE);
                }
                if (existingManifest != null) {
                    requireSource(source, observation.scope());
                    invalidate(session, observation.scope());
                    return new StageDecision(StageResult.QUARANTINED, Capacity.NONE);
                }
                if (existingMembership != null) {
                    requireSource(source, observation.scope());
                    invalidate(session, observation.scope());
                    return new StageDecision(StageResult.QUARANTINED, Capacity.NONE);
                }
                if (source == null) {
                    if (candidates.find(session, eq("scope", scopeId(observation.scope()))).hintString("merchant_seal_batch").limit(1).first() != null
                            || manifest.find(session, eq("scope", scopeId(observation.scope())))
                            .hintString("merchant_manifest_sequence").limit(1).first() != null
                            || membership.find(session, eq("scope", scopeId(observation.scope())))
                            .hintString(MongoMerchantObservationSchema.MEMBERSHIP_WINDOW).limit(1).first() != null
                            || membershipTips.find(session, eq("scope", scopeId(observation.scope())))
                            .hintString("merchant_membership_tip_scope").limit(1).first() != null
                            || history.find(session, eq("scope", scopeId(observation.scope())))
                            .hintString(MongoMerchantObservationSchema.HISTORY_WINDOW).limit(1).first() != null) {
                        throw failure("COVERAGE_MISSING");
                    }
                    source = sourceDocument(observation.scope(), now);
                    coverage.insertOne(session, source);
                } else {
                    requireSource(source, observation.scope());
                    source = compactExpired(session, source, observation.scope(), now);
                }
                if (invalidated(source)) {
                    return new StageDecision(StageResult.QUARANTINED, Capacity.NONE);
                }
                // Resolve indexed integrity conflicts before sequence and capacity dispositions can hide them.
                // Index hints fail closed; unique indexes remain the final concurrency arbiter.
                if (hasIdentityConflict(session, observation)) { throw failure("IDENTITY_CONFLICT"); }
                if (observation.sequence() <= source.getLong("sealedSequence")) {
                    invalidate(session, observation.scope());
                    return new StageDecision(StageResult.QUARANTINED, Capacity.NONE);
                }
                boolean late = source.getDate("watermark") != null
                        && !observation.eventTime().isAfter(instant(source, "watermark"));
                if (source.getInteger("pending") >= limits.maxPending()) {
                    // An admitted observation rejected before persistence may belong to any retained window,
                    // including one before the first watermark. Fence the exact scope before reporting capacity.
                    invalidate(session, observation.scope());
                    return new StageDecision(null, Capacity.PENDING);
                }
                if (source.getInteger("accepted") >= limits.maxStoredObservations()) {
                    invalidate(session, observation.scope());
                    return new StageDecision(null, late ? Capacity.LATE_EVIDENCE : Capacity.STORAGE);
                }
                Document candidate = observationDocument(observation).append("status", late ? "QUARANTINED" : "STAGED")
                        .append("stagedAt", Date.from(now));
                if (late) {
                    candidate.append("receiptExpireAt", Date.from(receiptExpiration(now, observation)))
                            .append("terminalReason", "POST_SEAL_EVENT");
                }
                candidates.insertOne(session, candidate);
                manifest.insertOne(session, observationDocument(observation));
                coverage.updateOne(session, eq("_id", source.getString("_id")), late
                        ? combine(inc("revision", 1L), inc("accepted", 1), inc("quarantined", 1), set("invalidated", true))
                        : combine(inc("revision", 1L), inc("accepted", 1), inc("pending", 1)));
                return new StageDecision(late ? StageResult.QUARANTINED : StageResult.CREATED, Capacity.NONE);
            });
            if (decision.capacity() == Capacity.PENDING) { throw failure("PENDING_CAPACITY"); }
            if (decision.capacity() == Capacity.STORAGE) { throw failure("STORAGE_CAPACITY"); }
            if (decision.capacity() == Capacity.LATE_EVIDENCE) { throw failure("LATE_EVIDENCE_CAPACITY"); }
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
        AtomicReference<SealFence> sourceFence = new AtomicReference<>();
        try {
            transaction(session -> {
                Document source = source(session, seal.scope());
                requireSource(source, seal.scope());
                sourceFence.set(SealFence.from(source));
                if (invalidated(source)) { throw failure("COVERAGE_INVALIDATED"); }
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
                List<Document> receipts = manifest.find(session, and(eq("scope", scopeId(seal.scope())),
                                gt("sequence", previous), lte("sequence", seal.throughSequence())))
                        .sort(ascending("sequence")).hintString("merchant_manifest_sequence")
                        .limit(limits.maxSealBatch() + 1).into(new ArrayList<>());
                if (batch.size() != delta || !matchesManifestBatch(batch, receipts)) { throw failure("SOURCE_SEQUENCE_GAP"); }
                long expected = previous;
                for (Document row : batch) {
                    Observation observation = decodeObservation(row);
                    if (observation.sequence() != ++expected || observation.eventTime().isAfter(seal.watermark())
                            || "QUARANTINED".equals(row.getString("status"))) { throw failure("SOURCE_SEQUENCE_GAP"); }
                }
                int newMembershipTips = recordMembershipBatch(session, seal.scope(), batch, previous,
                        source.getLong("compactedThroughSequence"), source.getInteger("membershipTipCount"));
                Instant retainedFrom = later(instant(source, "retainedFrom"), now().minus(limits.historyRetention()));
                if (coverage.updateOne(session, and(eq("_id", source.getString("_id")), eq("revision", source.getLong("revision"))),
                        combine(set("sealedSequence", seal.throughSequence()), set("coverageStart", Date.from(seal.coverageStart())),
                                set("watermark", Date.from(seal.watermark())), set("retainedFrom", Date.from(retainedFrom)),
                                inc("membershipTipCount", newMembershipTips), inc("revision", 1L))).getModifiedCount() != 1) {
                    throw race();
                }
                return null;
            });
        } catch (IllegalStateException exception) {
            String code = membershipIntegrityFailureCode(exception);
            if (code == null) { throw exception; }
            SealFence fence = sourceFence.get();
            if (fence == null) { throw failure("MEMBERSHIP_INTEGRITY_UNRESOLVED"); }
            invalidateAfterMembershipIntegrityFailure(seal.scope(), fence);
            throw failure(code);
        } catch (MongoException exception) {
            Document durable = safeRead(coverage, scopeId(seal.scope()));
            if (durable != null && invalidated(durable)) { throw failure("COVERAGE_INVALIDATED"); }
            if (!matchesSeal(durable, seal)) { throw failure("SEAL_UNRESOLVED"); }
        }
    }

    private static String membershipIntegrityFailureCode(IllegalStateException exception) {
        String message = exception.getMessage();
        if (message == null || !message.startsWith("MERCHANT_STORE_")) { return null; }
        String code = message.substring("MERCHANT_STORE_".length());
        return Set.of("MEMBERSHIP_TIP_MISSING", "MEMBERSHIP_TIP_SET_MISMATCH", "MEMBERSHIP_TIP_MISMATCH",
                "MEMBERSHIP_LINEAGE_GAP", "MEMBERSHIP_ORDER", "CORRUPT_MEMBERSHIP",
                "CORRUPT_MEMBERSHIP_TIP").contains(code) ? code : null;
    }

    private void invalidateAfterMembershipIntegrityFailure(Scope scope, SealFence fence) {
        if (fence.revision() == Long.MAX_VALUE) { throw membershipIntegrityUnresolved(); }
        try {
            transaction(session -> {
                Document current = source(session, scope);
                requireSource(current, scope);
                if (fence.matches(current, scope, true)) { return null; }
                if (!fence.matches(current, scope, false)) { throw membershipIntegrityUnresolved(); }
                if (coverage.updateOne(session, fence.invalidationFilter(scope),
                        combine(set("invalidated", true), inc("revision", 1L))).getModifiedCount() != 1) {
                    throw membershipIntegrityUnresolved();
                }
                return null;
            });
        } catch (MongoException exception) {
            if (hasDurableMembershipIntegrityFence(scope, fence)) { return; }
            throw membershipIntegrityUnresolved();
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException state
                    && "MERCHANT_STORE_MEMBERSHIP_INTEGRITY_UNRESOLVED".equals(state.getMessage())) {
                throw state;
            }
            throw membershipIntegrityUnresolved();
        }
    }

    private boolean hasDurableMembershipIntegrityFence(Scope scope, SealFence fence) {
        try {
            Document durable = safeRead(coverage, scopeId(scope));
            requireSource(durable, scope);
            return fence.matches(durable, scope, true);
        } catch (RuntimeException unavailableOrChanged) {
            return false;
        }
    }

    private static IllegalStateException membershipIntegrityUnresolved() {
        return failure("MEMBERSHIP_INTEGRITY_UNRESOLVED");
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
                Document receipt = manifest.find(session, eq("_id", eventId(observation))).first();
                if (!matchesManifest(candidate, receipt)) {
                    if ("STAGED".equals(candidate.getString("status"))) {
                        return quarantine(session, candidate, observation.scope(), Reason.HISTORY_MISSING);
                    }
                    if (!invalidated(source)) { invalidate(session, observation.scope()); }
                    return "COMMITTED".equals(candidate.getString("status"))
                            ? new CommitResult(CommitStatus.EXISTING, unavailable(observation, Reason.HISTORY_MISSING))
                            : new CommitResult(CommitStatus.QUARANTINED, null);
                }
                CommitResult terminal = terminalResult(session, candidate, observation, source);
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
                MerchantObservationWindowPolicy.Result preflight = deriveWindow(observation, source, List.of(), now());
                if (preflight.reason() != com.frauddetection.common.events.merchant.MerchantProfile.Reason.NONE
                        || preflight.windows().getFirst().status() == com.frauddetection.common.events.merchant.MerchantProfile.Status.UNAVAILABLE) {
                    snapshot = snapshotFromDerived(observation, preflight);
                } else {
                    List<Document> rows = history.find(session, window).sort(ascending("eventTime", "sequence"))
                            .hintString(MongoMerchantObservationSchema.HISTORY_WINDOW).limit(limits.maxHistoryRows() + 1)
                            .into(new ArrayList<>());
                    List<Document> expected = candidates.find(session, and(window, eq("status", "COMMITTED")))
                            .sort(ascending("eventTime", "sequence")).hintString(MongoMerchantObservationSchema.CANDIDATE_WINDOW)
                            .limit(limits.maxHistoryRows() + 1).into(new ArrayList<>());
                    List<Document> receipts = manifest.find(session, window).sort(ascending("eventTime", "sequence"))
                            .hintString(MongoMerchantObservationSchema.MANIFEST_WINDOW).limit(limits.maxHistoryRows() + 1)
                            .into(new ArrayList<>());
                    List<Document> members = membership.find(session, window).sort(ascending("eventTime", "sequence"))
                            .hintString(MongoMerchantObservationSchema.MEMBERSHIP_WINDOW).limit(limits.maxHistoryRows() + 1)
                            .into(new ArrayList<>());
                    Document currentMember = membership.find(session, eq("_id", eventId(observation))).first();
                    if (rows.size() > limits.maxHistoryRows() || expected.size() > limits.maxHistoryRows()
                            || receipts.size() > limits.maxHistoryRows() || members.size() > limits.maxHistoryRows()) {
                        return quarantine(session, candidate, observation.scope(), Reason.CAPACITY_EXCEEDED);
                    }
                    if (!matchesEvidence(session, rows, expected, receipts, members, currentMember, observation, start,
                            source.getLong("compactedThroughSequence"), source.getLong("sealedSequence"))) {
                        return quarantine(session, candidate, observation.scope(), Reason.HISTORY_MISSING);
                    }
                    MerchantObservationWindowPolicy.Result derived = deriveWindow(observation, source, rows, now());
                    snapshot = snapshotFromDerived(observation, derived);
                }
                history.insertOne(session, observationDocument(observation)
                        .append("expireAt", Date.from(later(now(), observation.eventTime()).plus(limits.historyRetention()))));
                if (candidates.updateOne(session, and(eq("_id", eventId(observation)), eq("status", "STAGED")),
                        combine(set("status", "COMMITTED"), set("receiptExpireAt", Date.from(receiptExpiration(now(), observation))),
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
                CommitResult reconciled = terminalResult(null, durable, observation, source);
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
                if (!matchesManifest(row, manifest.find(session, eq("_id", eventId(observation))).first())) {
                    return unavailable(observation, Reason.HISTORY_MISSING);
                }
                return currentSnapshot(session, stored, source, observation, now());
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
                        set("receiptExpireAt", Date.from(receiptExpiration(now(), decodeObservation(candidate)))))).getModifiedCount() != 1) {
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

    /** Source sequence appends lineage; event time is used only to answer historical windows. */
    private int recordMembershipBatch(ClientSession session, Scope scope, List<Document> batch,
                                      long previousSealedSequence, long compactedThroughSequence, int expectedTipCount) {
        List<Document> existingTips = membershipTips.find(session, eq("scope", scopeId(scope)))
                .hintString("merchant_membership_tip_scope").limit(limits.maxStoredObservations() + 1)
                .into(new ArrayList<>());
        int existingTipCount = existingTips.size();
        if (existingTipCount < expectedTipCount) { throw failure("MEMBERSHIP_TIP_MISSING"); }
        if (existingTipCount != expectedTipCount) { throw failure("MEMBERSHIP_TIP_SET_MISMATCH"); }
        int newTipCount = 0;
        Map<String, List<Observation>> byMerchant = new HashMap<>();
        for (Document candidate : batch) {
            Observation observation = decodeObservation(candidate);
            byMerchant.computeIfAbsent(observation.merchant(), ignored -> new ArrayList<>()).add(observation);
        }
        for (List<Observation> merchantBatch : byMerchant.values()) {
            merchantBatch.sort(Comparator.comparingLong(Observation::sequence));
            Observation first = merchantBatch.getFirst();
            String tipId = membershipTipId(scope, first.merchant());
            MembershipLineage lineage = membershipLineage(session, scope, first.merchant(), compactedThroughSequence,
                    previousSealedSequence);
            MembershipCheckpoint previous = null;
            MembershipAnchor compactedCheckpoint = null;
            if (lineage == null) {
                if (existingTipCount + newTipCount >= limits.maxStoredObservations()) {
                    throw failure("STORAGE_CAPACITY");
                }
                newTipCount++;
            } else {
                MembershipTip tip = lineage.tip();
                previous = lineage.retained().isEmpty() ? tip.checkpoint()
                        : lineage.retained().getLast();
                compactedCheckpoint = tip.compactedCheckpoint();
            }
            for (Observation observation : merchantBatch) {
                Document checkpoint = membershipDocument(observation, previous);
                membership.insertOne(session, checkpoint);
                previous = decodeMembership(checkpoint);
            }
            membershipTips.replaceOne(session, eq("_id", tipId), membershipTipDocument(previous, compactedCheckpoint, false),
                    new ReplaceOptions().upsert(true));
        }
        return newTipCount;
    }

    /** The full retained chain is capacity-bounded and fetched only through its scope/merchant/sequence index. */
    private MembershipLineage membershipLineage(ClientSession session, Scope scope, String merchant,
                                                long compactedThroughSequence, long sealedSequence) {
        String tipId = membershipTipId(scope, merchant);
        var tipQuery = session == null ? membershipTips.find(eq("_id", tipId))
                : membershipTips.find(session, eq("_id", tipId));
        Document tipDocument = tipQuery.first();
        var membershipQuery = session == null
                ? membership.find(and(eq("scope", scopeId(scope)), eq("merchant", merchant)))
                : membership.find(session, and(eq("scope", scopeId(scope)), eq("merchant", merchant)));
        List<Document> rows = membershipQuery
                .sort(ascending("sequence")).hintString(MongoMerchantObservationSchema.MEMBERSHIP_CHAIN)
                .limit(limits.maxStoredObservations() + 1).into(new ArrayList<>());
        if (rows.size() > limits.maxStoredObservations()) { throw failure("MEMBERSHIP_CAPACITY"); }
        if (tipDocument == null) {
            if (!rows.isEmpty()) { throw failure("MEMBERSHIP_TIP_MISSING"); }
            return null;
        }
        MembershipTip tip = decodeMembershipTip(tipDocument);
        MembershipCheckpoint tipCheckpoint = tip.checkpoint();
        MembershipAnchor anchor = tip.compactedCheckpoint();
        if (!tipCheckpoint.scope().equals(scope) || !tipCheckpoint.merchant().equals(merchant)
                || tipCheckpoint.sequence() > sealedSequence
                || anchor != null && anchor.sequence() > compactedThroughSequence
                || tip.compacted() && tipCheckpoint.sequence() > compactedThroughSequence
                || !tip.compacted() && tipCheckpoint.sequence() <= compactedThroughSequence) {
            throw failure("MEMBERSHIP_TIP_MISMATCH");
        }
        List<MembershipCheckpoint> retained = new ArrayList<>(rows.size());
        Map<String, MembershipCheckpoint> retainedById = new HashMap<>();
        MembershipCheckpoint predecessor = null;
        for (Document row : rows) {
            MembershipCheckpoint checkpoint = decodeMembership(row);
            if (!checkpoint.scope().equals(scope) || !checkpoint.merchant().equals(merchant)
                    || checkpoint.sequence() <= compactedThroughSequence || checkpoint.sequence() > tipCheckpoint.sequence()) {
                throw failure("CORRUPT_MEMBERSHIP");
            }
            boolean linked = predecessor != null ? membershipMatchesPredecessor(checkpoint, predecessor)
                    : anchor == null ? membershipHasNoPredecessor(checkpoint)
                    : membershipMatchesAnchor(checkpoint, anchor);
            if (!linked) { throw failure("MEMBERSHIP_LINEAGE_GAP"); }
            if (retainedById.put(checkpoint.id(), checkpoint) != null) { throw failure("CORRUPT_MEMBERSHIP"); }
            retained.add(checkpoint);
            predecessor = checkpoint;
        }
        if (tip.compacted()) {
            if (!retained.isEmpty() || anchor == null) { throw failure("MEMBERSHIP_TIP_MISMATCH"); }
        } else if (retained.isEmpty() || !tipCheckpoint.equals(retained.getLast())) {
            throw failure("MEMBERSHIP_TIP_MISMATCH");
        }
        return new MembershipLineage(tip, List.copyOf(retained), Map.copyOf(retainedById));
    }

    private boolean matchesEvidence(ClientSession session, List<Document> rows, List<Document> expected, List<Document> receipts,
                                    List<Document> members, Document currentMember, Observation current, Instant windowStart,
                                    long compactedThroughSequence, long sealedSequence) {
        if (rows.size() != expected.size() || rows.size() != receipts.size() || rows.size() != members.size()
                || !membershipMatchesObservation(currentMember, current)) { return false; }
        MembershipLineage lineage;
        MembershipCheckpoint currentCheckpoint;
        try {
            currentCheckpoint = decodeMembership(currentMember);
            lineage = membershipLineage(session, current.scope(), current.merchant(), compactedThroughSequence, sealedSequence);
            if (lineage == null) { return false; }
            for (Document member : members) {
                MembershipCheckpoint checkpoint = decodeMembership(member);
                if (!checkpoint.scope().equals(current.scope()) || !checkpoint.merchant().equals(current.merchant())
                        || !checkpoint.equals(lineage.retainedById().get(checkpoint.id()))) { return false; }
            }
        } catch (IllegalStateException corrupt) { return false; }
        if (!currentCheckpoint.equals(lineage.retainedById().get(currentCheckpoint.id()))) { return false; }
        Set<String> expectedWindowMembers = new HashSet<>();
        for (MembershipCheckpoint checkpoint : lineage.retained()) {
            if (!checkpoint.eventTime().isBefore(windowStart) && checkpoint.eventTime().isBefore(current.eventTime())) {
                expectedWindowMembers.add(checkpoint.id());
            }
        }
        Set<String> actualWindowMembers = new HashSet<>();
        for (Document member : members) {
            if (!actualWindowMembers.add(member.getString("_id"))) { return false; }
        }
        if (!expectedWindowMembers.equals(actualWindowMembers)) { return false; }
        for (int index = 0; index < rows.size(); index++) {
            Document row = rows.get(index);
            Document candidate = expected.get(index);
            Document receipt = receipts.get(index);
            Document member = members.get(index);
            if (!Objects.equals(row.getString("_id"), candidate.getString("_id"))
                    || !Objects.equals(row.getString("_id"), receipt.getString("_id"))
                    || !Objects.equals(row.getString("_id"), member.getString("_id"))
                    || !Objects.equals(row.getString("inputCommitment"), candidate.getString("inputCommitment"))
                    || !Objects.equals(row.getString("inputCommitment"), receipt.getString("inputCommitment"))
                    || !Objects.equals(row.getString("inputCommitment"), member.getString("inputCommitment"))
                    || row.getDate("expireAt") == null || !instant(row, "expireAt").isAfter(now())
                    || !decodeObservation(row).equals(decodeObservation(candidate))
                    || !decodeObservation(row).equals(decodeObservation(receipt))
                    || !membershipMatchesObservation(member, decodeObservation(row))) { return false; }
        }
        return true;
    }

    private boolean matchesManifest(Document candidate, Document receipt) {
        return candidate != null && receipt != null
                && Objects.equals(candidate.getString("_id"), receipt.getString("_id"))
                && Objects.equals(candidate.getString("inputCommitment"), receipt.getString("inputCommitment"))
                && decodeObservation(candidate).equals(decodeObservation(receipt));
    }

    private boolean matchesManifestBatch(List<Document> batch, List<Document> receipts) {
        if (batch.size() != receipts.size()) { return false; }
        for (int index = 0; index < batch.size(); index++) {
            if (!matchesManifest(batch.get(index), receipts.get(index))) { return false; }
        }
        return true;
    }

    /** Transactional prefix compaction; receipts stay through replay/history retention and sequence never resets. */
    private Document compactExpired(ClientSession session, Document source, Scope scope, Instant now) {
        long floor = source.getLong("compactedThroughSequence");
        long sealed = source.getLong("sealedSequence");
        if (floor == sealed) { return source; }
        List<Document> receipts = manifest.find(session, and(eq("scope", scopeId(scope)), gt("sequence", floor), lte("sequence", sealed)))
                .sort(ascending("sequence")).hintString("merchant_manifest_sequence").limit(limits.maxSealBatch() + 1)
                .into(new ArrayList<>());
        if (receipts.isEmpty() || receipts.getFirst().getLong("sequence") != floor + 1) {
            invalidate(session, scope);
            return source(session, scope);
        }

        List<Document> compact = new ArrayList<>();
        Map<String, MembershipLineage> lineages = new HashMap<>();
        Map<String, MembershipCheckpoint> latestCompactedByMerchant = new HashMap<>();
        long expectedSequence = floor;
        int quarantined = 0;
        Instant retainedFrom = later(instant(source, "retainedFrom"), now.minus(limits.historyRetention()));
        for (Document receipt : receipts) {
            Observation observation = decodeObservation(receipt);
            if (observation.sequence() != ++expectedSequence) {
                invalidate(session, scope);
                return source(session, scope);
            }
            Document candidate = candidates.find(session, eq("_id", receipt.getString("_id"))).first();
            if (!matchesManifest(candidate, receipt)) {
                invalidate(session, scope);
                return source(session, scope);
            }
            Document checkpoint = membership.find(session, eq("_id", receipt.getString("_id"))).first();
            if (!membershipMatchesObservation(checkpoint, observation)) {
                invalidate(session, scope);
                return source(session, scope);
            }
            MembershipLineage lineage;
            try {
                lineage = lineages.computeIfAbsent(observation.merchant(), merchant ->
                        membershipLineage(session, scope, merchant, floor, sealed));
            } catch (IllegalStateException corruptLineage) {
                invalidate(session, scope);
                return source(session, scope);
            }
            MembershipCheckpoint decodedCheckpoint = decodeMembership(checkpoint);
            if (lineage == null || !decodedCheckpoint.equals(lineage.retainedById().get(decodedCheckpoint.id()))) {
                invalidate(session, scope);
                return source(session, scope);
            }
            String status = candidate.getString("status");
            if (!"COMMITTED".equals(status) && !"QUARANTINED".equals(status)) { break; }
            Date receiptExpiry = candidate.getDate("receiptExpireAt");
            if (receiptExpiry == null || receiptExpiry.toInstant().isAfter(now)) { break; }
            // Keep every retained/readable event-time window outside the compacted prefix.
            if (!observation.eventTime().isBefore(retainedFrom)) { break; }
            Document storedHistory = history.find(session, eq("_id", receipt.getString("_id"))).first();
            if (storedHistory != null && (!decodeObservation(storedHistory).equals(observation)
                    || storedHistory.getDate("expireAt") == null || instant(storedHistory, "expireAt").isAfter(now))) { break; }
            compact.add(candidate);
            latestCompactedByMerchant.put(observation.merchant(), decodedCheckpoint);
            if ("QUARANTINED".equals(status)) { quarantined++; }
            if (compact.size() == limits.maxSealBatch()) { break; }
        }
        if (compact.isEmpty()) { return source; }

        long lastSequence = decodeObservation(compact.getLast()).sequence();
        for (Document candidate : compact) {
            String id = candidate.getString("_id");
            if (candidates.deleteOne(session, and(eq("_id", id), ne("status", "STAGED"))).getDeletedCount() != 1
                    || manifest.deleteOne(session, eq("_id", id)).getDeletedCount() != 1
                    || membership.deleteOne(session, eq("_id", id)).getDeletedCount() != 1) { throw race(); }
            history.deleteOne(session, eq("_id", id));
        }
        for (Map.Entry<String, MembershipCheckpoint> compactedEntry : latestCompactedByMerchant.entrySet()) {
            String tipId = membershipTipId(scope, compactedEntry.getKey());
            Document tipDocument = membershipTips.find(session, eq("_id", tipId)).first();
            if (tipDocument == null) { throw race(); }
            MembershipTip tip = decodeMembershipTip(tipDocument);
            MembershipCheckpoint compactedCheckpoint = compactedEntry.getValue();
            MembershipAnchor anchor = new MembershipAnchor(compactedCheckpoint.id(), compactedCheckpoint.sequence(),
                    compactedCheckpoint.eventTime(), compactedCheckpoint.commitment());
            boolean tipCompacted = tip.checkpoint().sequence() <= lastSequence;
            if (tipCompacted && !tip.checkpoint().equals(compactedCheckpoint)) { throw race(); }
            if (membershipTips.replaceOne(session, eq("_id", tipId),
                    membershipTipDocument(tip.checkpoint(), anchor, tipCompacted)).getMatchedCount() != 1) {
                throw race();
            }
        }
        if (coverage.updateOne(session, and(eq("_id", source.getString("_id")), eq("revision", source.getLong("revision")),
                        gte("accepted", compact.size()), gte("quarantined", quarantined)),
                combine(inc("accepted", -compact.size()), inc("quarantined", -quarantined),
                        set("compactedThroughSequence", lastSequence), set("retainedFrom", Date.from(retainedFrom)), inc("revision", 1L)))
                .getModifiedCount() != 1) { throw race(); }
        return source(session, scope);
    }

    private Snapshot unavailable(Observation observation, Reason reason) {
        return new Snapshot(observation.eventTime().minus(limits.window()), observation.eventTime(), Availability.UNAVAILABLE, null, reason);
    }

    private CommitResult terminalResult(ClientSession session, Document row, Observation observation, Document source) {
        return switch (row.getString("status")) {
            case "COMMITTED" -> new CommitResult(CommitStatus.EXISTING,
                    session == null ? readSnapshot(observation)
                            : currentSnapshot(session, decodeSnapshot(row, observation), source, observation, now()));
            case "QUARANTINED" -> new CommitResult(CommitStatus.QUARANTINED, null);
            case "STAGED" -> null;
            default -> throw failure("CANDIDATE_STATE");
        };
    }

    /** Revoke usability without rewriting the immutable receipt when coverage or retained history is no longer sufficient. */
    private Snapshot currentSnapshot(ClientSession session, Snapshot stored, Document source, Observation observation, Instant evaluatedAt) {
        MerchantObservationWindowPolicy.Result preflight = deriveWindow(observation, source, List.of(), evaluatedAt);
        if (preflight.reason() != com.frauddetection.common.events.merchant.MerchantProfile.Reason.NONE
                || preflight.windows().getFirst().status() == com.frauddetection.common.events.merchant.MerchantProfile.Status.UNAVAILABLE) {
            return snapshotFromDerived(observation, preflight);
        }
        if (stored.availability() == Availability.COMPLETE_WINDOW) {
            Bson window = window(observation, stored.windowStart());
            List<Document> rows = history.find(session, window).sort(ascending("eventTime", "sequence"))
                    .hintString(MongoMerchantObservationSchema.HISTORY_WINDOW).limit(limits.maxHistoryRows() + 1)
                    .into(new ArrayList<>());
            List<Document> expected = candidates.find(session, and(window, eq("status", "COMMITTED")))
                    .sort(ascending("eventTime", "sequence")).hintString(MongoMerchantObservationSchema.CANDIDATE_WINDOW)
                    .limit(limits.maxHistoryRows() + 1).into(new ArrayList<>());
            List<Document> receipts = manifest.find(session, window).sort(ascending("eventTime", "sequence"))
                    .hintString(MongoMerchantObservationSchema.MANIFEST_WINDOW).limit(limits.maxHistoryRows() + 1)
                    .into(new ArrayList<>());
            List<Document> members = membership.find(session, window).sort(ascending("eventTime", "sequence"))
                    .hintString(MongoMerchantObservationSchema.MEMBERSHIP_WINDOW).limit(limits.maxHistoryRows() + 1)
                    .into(new ArrayList<>());
            Document currentMember = membership.find(session, eq("_id", eventId(observation))).first();
            if (rows.size() > limits.maxHistoryRows() || expected.size() > limits.maxHistoryRows()
                    || receipts.size() > limits.maxHistoryRows() || members.size() > limits.maxHistoryRows()) {
                return unavailable(observation, Reason.CAPACITY_EXCEEDED);
            }
            if (!matchesEvidence(session, rows, expected, receipts, members, currentMember, observation,
                    stored.windowStart(), source.getLong("compactedThroughSequence"), source.getLong("sealedSequence"))) {
                return unavailable(observation, Reason.HISTORY_MISSING);
            }
            MerchantObservationWindowPolicy.Result derived = deriveWindow(observation, source, rows, evaluatedAt);
            Snapshot current = snapshotFromDerived(observation, derived);
            if (current.availability() != Availability.COMPLETE_WINDOW) { return current; }
            if (!current.priorCount().equals(stored.priorCount())) {
                return unavailable(observation, Reason.HISTORY_MISSING);
            }
        }
        return stored;
    }

    private MerchantObservationWindowPolicy.Result deriveWindow(Observation current, Document source,
                                                                 List<Document> historyRows, Instant evaluatedAt) {
        Instant start = current.eventTime().minus(limits.window());
        List<Observation> observations = historyRows.stream().map(MerchantObservationDocumentCodec::decodeObservation).toList();
        Instant evaluationFloor = later(evaluatedAt, current.eventTime());
        CoverageSeal seal = source.getDate("watermark") == null ? null
                : new CoverageSeal(current.scope(), source.getLong("sealedSequence"), instant(source, "coverageStart"),
                        instant(source, "watermark"));
        HistoryRead read = new HistoryRead(current,
                seal,
                start, current.eventTime(), evaluationFloor,
                later(instant(source, "retainedFrom"), evaluatedAt.minus(limits.historyRetention())), observations.size(),
                observations, invalidated(source));
        return MerchantObservationWindowPolicy.derive(read,
                List.of(new MerchantObservationWindowPolicy.Window(start, current.eventTime())), limits.maxHistoryRows());
    }

    private Snapshot snapshotFromDerived(Observation observation, MerchantObservationWindowPolicy.Result derived) {
        if (derived.reason() != com.frauddetection.common.events.merchant.MerchantProfile.Reason.NONE) {
            return unavailable(observation, storeReason(derived.reason()));
        }
        var window = derived.windows().getFirst();
        if (window.status() == com.frauddetection.common.events.merchant.MerchantProfile.Status.UNAVAILABLE) {
            return unavailable(observation, storeReason(window.reason()));
        }
        return new Snapshot(window.start(), window.end(), Availability.COMPLETE_WINDOW, window.count(), Reason.NONE);
    }

    private static Reason storeReason(com.frauddetection.common.events.merchant.MerchantProfile.Reason reason) {
        return switch (reason) {
            case RETENTION_EXPIRED -> Reason.RETENTION_EXPIRED;
            case CAPACITY_EXCEEDED -> Reason.CAPACITY_EXCEEDED;
            case HISTORY_MISSING, DATA_CONFLICT, SCOPE_MISMATCH -> Reason.HISTORY_MISSING;
            default -> Reason.INCOMPLETE_COVERAGE;
        };
    }

    private StageResult existingStage(Document row, Observation observation) {
        requireBinding(row, observation);
        requireReceiptFresh(row);
        if (!matchesManifest(row, safeRead(manifest, eventId(observation)))) { throw failure("HISTORY_MISSING"); }
        if ("COMMITTED".equals(row.getString("status"))) {
            Document member = safeRead(membership, eventId(observation));
            Document source = safeRead(coverage, scopeId(observation.scope()));
            if (!membershipMatchesObservation(member, observation) || source == null) { throw failure("HISTORY_MISSING"); }
            requireSource(source, observation.scope());
            MembershipLineage lineage = membershipLineage(null, observation.scope(), observation.merchant(),
                    source.getLong("compactedThroughSequence"), source.getLong("sealedSequence"));
            MembershipCheckpoint checkpoint = decodeMembership(member);
            if (lineage == null || !checkpoint.equals(lineage.retainedById().get(checkpoint.id()))) {
                throw failure("HISTORY_MISSING");
            }
        }
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

    private void requireSource(Document row, Scope scope) {
        if (row == null) { throw failure("COVERAGE_MISSING"); }
        try {
            if (!scope.equals(decodeScope(row)) || !scopeId(scope).equals(row.getString("_id"))) { throw failure("SCOPE_CONFLICT"); }
            if (!policyCommitment.equals(row.getString("policyCommitment"))) { throw failure("POLICY_MISMATCH"); }
            long sealedSequence = row.getLong("sealedSequence");
            long compactedThrough = row.getLong("compactedThroughSequence");
            int pending = row.getInteger("pending");
            int quarantined = row.getInteger("quarantined");
            if (pending < 0 || quarantined < 0 || sealedSequence < 0
                    || compactedThrough < 0 || compactedThrough > sealedSequence || row.getLong("revision") < 0
                    || row.getBoolean("invalidated") == null || row.getDate("retainedFrom") == null) {
                throw failure("CORRUPT_COVERAGE");
            }
            int accepted = row.getInteger("accepted");
            int membershipTipCount = row.getInteger("membershipTipCount");
            if (accepted < 0 || accepted > limits.maxStoredObservations() || accepted < sealedSequence - compactedThrough
                    || membershipTipCount < 0 || membershipTipCount > limits.maxStoredObservations()
                    || pending > limits.maxPending() || quarantined > accepted || (long) pending + quarantined > accepted
                    || (row.getDate("coverageStart") == null) != (row.getDate("watermark") == null)
                    || (row.getLong("sealedSequence") == 0) != (row.getDate("watermark") == null)) {
                throw failure("CORRUPT_COVERAGE");
            }
            instant(row, "retainedFrom");
            if (row.getDate("watermark") != null) {
                new CoverageSeal(scope, row.getLong("sealedSequence"), instant(row, "coverageStart"), instant(row, "watermark"));
            }
        } catch (IllegalStateException exception) { throw exception; }
        catch (RuntimeException exception) { throw failure("CORRUPT_COVERAGE"); }
    }

    private Document source(ClientSession session, Scope scope) { return coverage.find(session, eq("_id", scopeId(scope))).first(); }

    private Document sourceDocument(Scope scope, Instant now) {
        return scopeDocument(scope).append("_id", scopeId(scope)).append("revision", 0L).append("pending", 0)
                .append("accepted", 0).append("quarantined", 0).append("membershipTipCount", 0).append("sealedSequence", 0L)
                .append("compactedThroughSequence", 0L).append("retainedFrom", Date.from(now.minus(limits.historyRetention())))
                .append("invalidated", false).append("policyCommitment", policyCommitment);
    }

    private boolean hasIdentityConflict(ClientSession session, Observation observation) {
        try {
            for (String identity : List.of("transaction", "occurrence", "sequence")) {
                Bson key = and(exactScope(observation.scope()),
                        "sequence".equals(identity) ? eq("sequence", observation.sequence())
                                : eq(identity, "transaction".equals(identity) ? observation.transaction() : observation.occurrence()));
                var query = session == null ? candidates.find(key) : candidates.find(session, key);
                if (query.hintString("merchant_" + identity + "_unique").limit(1).first() != null) { return true; }
                var manifestQuery = session == null ? manifest.find(key) : manifest.find(session, key);
                if (manifestQuery.hintString("merchant_manifest_" + identity + "_unique").limit(1).first() != null) { return true; }
                if ("sequence".equals(identity)) {
                    var membershipQuery = session == null ? membership.find(key) : membership.find(session, key);
                    if (membershipQuery.hintString("merchant_membership_sequence_unique").limit(1).first() != null) { return true; }
                }
            }
            return false;
        } catch (MongoException exception) {
            if (session != null) { throw exception; }
            throw failure("CONFLICT_READ_UNAVAILABLE");
        }
    }

    private static Bson exactScope(Scope scope) {
        return and(eq("issuer", scope.issuer()), eq("institution", scope.institution()), eq("source", scope.source()),
                eq("epoch", scope.epoch()), eq("identityVersion", scope.identityVersion()));
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

    private static String snapshotCommitment(Observation observation, Snapshot snapshot) {
        return MerchantCommitment.sha256("MERCHANT_STORE_DIGEST_UNAVAILABLE", "merchant-snapshot/v2",
                inputCommitment(observation), snapshot.windowStart(), snapshot.asOf(),
                snapshot.availability(), snapshot.priorCount(), snapshot.reason());
    }
    private Instant now() { return Instant.ofEpochMilli(clock.millis()); }
    private Instant receiptExpiration(Instant now, Observation observation) {
        return later(now.plus(limits.receiptRetention()),
                observation.eventTime().plus(limits.historyRetention()).plus(limits.clockSkew()));
    }
    private static Instant instant(Document row, String field) { return row.getDate(field).toInstant(); }
    private static boolean invalidated(Document source) { return Boolean.TRUE.equals(source.getBoolean("invalidated")); }
    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
    private static IllegalStateException failure(String code) { return new IllegalStateException("MERCHANT_STORE_" + code); }
    private static MongoException race() {
        MongoException exception = new MongoException(112, "MERCHANT_STORE_WRITE_RACE");
        exception.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return exception;
    }
    private record SealFence(long revision, long sealedSequence, long compactedThroughSequence,
                             int membershipTipCount,
                             Instant coverageStart, Instant watermark, Instant retainedFrom,
                             String policyCommitment) {
        private static SealFence from(Document source) {
            return new SealFence(source.getLong("revision"), source.getLong("sealedSequence"),
                    source.getLong("compactedThroughSequence"),
                    source.getInteger("membershipTipCount"), nullableInstant(source, "coverageStart"),
                    nullableInstant(source, "watermark"), nullableInstant(source, "retainedFrom"),
                    source.getString("policyCommitment"));
        }

        private boolean matches(Document source, Scope scope, boolean invalidated) {
            return source != null && scope.equals(decodeScope(source))
                    && scopeId(scope).equals(source.getString("_id"))
                    && Objects.equals(policyCommitment, source.getString("policyCommitment"))
                    && source.getLong("revision") >= revision + (invalidated ? 1L : 0L)
                    && Objects.equals(sealedSequence, source.getLong("sealedSequence"))
                    && Objects.equals(compactedThroughSequence, source.getLong("compactedThroughSequence"))
                    && Objects.equals(membershipTipCount, source.getInteger("membershipTipCount"))
                    && Objects.equals(coverageStart, nullableInstant(source, "coverageStart"))
                    && Objects.equals(watermark, nullableInstant(source, "watermark"))
                    && Objects.equals(retainedFrom, nullableInstant(source, "retainedFrom"))
                    && Objects.equals(invalidated, source.getBoolean("invalidated"));
        }

        private Bson invalidationFilter(Scope scope) {
            return and(eq("_id", scopeId(scope)), exactScope(scope), eq("policyCommitment", policyCommitment),
                    gte("revision", revision), eq("sealedSequence", sealedSequence),
                    eq("compactedThroughSequence", compactedThroughSequence),
                    eq("membershipTipCount", membershipTipCount), eq("coverageStart", asDate(coverageStart)),
                    eq("watermark", asDate(watermark)), eq("retainedFrom", asDate(retainedFrom)),
                    eq("invalidated", false));
        }

        private static Instant nullableInstant(Document source, String field) {
            Date value = source.getDate(field);
            return value == null ? null : value.toInstant();
        }

        private static Date asDate(Instant value) { return value == null ? null : Date.from(value); }
    }
    private enum Capacity { NONE, PENDING, STORAGE, LATE_EVIDENCE }
    private record MembershipLineage(MembershipTip tip, List<MembershipCheckpoint> retained,
                                    Map<String, MembershipCheckpoint> retainedById) {
        @Override public String toString() { return "MerchantMembershipLineage[redacted]"; }
    }
    private record StageDecision(StageResult result, Capacity capacity) { }
}
