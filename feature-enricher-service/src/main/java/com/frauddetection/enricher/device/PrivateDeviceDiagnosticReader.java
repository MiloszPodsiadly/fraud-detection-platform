package com.frauddetection.enricher.device;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.common.events.device.policy.DeviceRiskSignalPolicy;
import com.frauddetection.common.events.source.*;
import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Projections.include;

/** Read-only, private Stage B. It returns only the existing policy's bounded, non-identifying interpretation. */
final class PrivateDeviceDiagnosticReader {
    static final String PURPOSE = "DEVICE_PRIVATE_DIAGNOSTIC_READ";
    static final String AUDIENCE = "device-diagnostics-private";
    static final Set<String> COLLECTIONS = Set.of("device_occurrences", "device_observation_candidates", "device_source_coverage",
            "device_observations", "device_operational_revocation_tombstones");
    private static final int MAX_VERIFIED_HISTORY_MEMBERS = 1_000;
    private static final Bson OBSERVATION_HISTORY_PROJECTION = include("_id", "schemaVersion", "identityVersion", "sourceIssuer",
            "sourceEpoch", "institution", "source", "sourceEvent", "customer", "device", "keyVersion", "eventTime", "expireAt",
            "provenance", "provenanceCommitment", "membershipIntegrityCommitment");
    private static final Bson OCCURRENCE_HISTORY_PROJECTION = include("_id", "schemaVersion", "identityVersion", "sourceIssuer",
            "sourceEpoch", "institution", "source", "sourceEvent", "transaction", "customer", "device", "keyVersion", "sequence",
            "eventTime", "sourceObservedAt", "payloadCommitment", "evidenceCommitment", "provenance", "provenanceCommitment",
            "candidateIntegrityCommitment", "membershipIntegrityCommitment", "stageACommitment", "snapshotIntegrityCommitment",
            "populationCommitment", "coverageCommitment", "coverageAuthority", "coverageStart", "expireAt",
            "historyMembershipCount", "historyMembershipCommitment");
    private static final Bson CANDIDATE_HISTORY_PROJECTION = include("_id", "schemaVersion", "identityVersion", "sourceIssuer",
            "sourceEpoch", "institution", "source", "sourceEvent", "transaction", "customer", "device", "keyVersion", "sequence",
            "eventTime", "sourceObservedAt", "payloadCommitment", "provenance", "provenanceCommitment", "candidateIntegrityCommitment",
            "candidateStateIntegrityCommitment", "status", "terminalReason", "terminalExpireAt");
    private static final Duration REPAIR_HORIZON = Duration.ofDays(1).plusMinutes(5);
    private final MongoClient client;
    private final MongoDatabase db;
    private final ReadGrant grant;
    private final DeviceEpoch epoch;
    private final MongoSourceLineage source;
    private final SourceOccurrenceAccess scored;
    private final DeviceAssertionVerifier verifier;
    private final DevicePseudonyms pseudonyms;
    private final Clock clock;
    private final Runnable afterSnapshotRead;
    private final Runnable afterHistoricalGrantVerification;
    private final Runnable afterHistoricalCoverageVerification;
    private final DeviceRiskSignalPolicy policy = new DeviceRiskSignalPolicy();

    PrivateDeviceDiagnosticReader(MongoClient client, String database, ReadGrant grant, MongoSourceLineage source,
                                  SourceOccurrenceAccess scored, DeviceAssertionVerifier verifier, DevicePseudonyms pseudonyms, Clock clock) {
        this(client, database, grant, source, scored, verifier, pseudonyms, clock, () -> { });
    }

    PrivateDeviceDiagnosticReader(MongoClient client, String database, ReadGrant grant, MongoSourceLineage source,
                                  SourceOccurrenceAccess scored, DeviceAssertionVerifier verifier, DevicePseudonyms pseudonyms,
                                  Clock clock, Runnable afterSnapshotRead) {
        this(client, database, grant, source, scored, verifier, pseudonyms, clock, afterSnapshotRead, () -> { }, () -> { });
    }

    PrivateDeviceDiagnosticReader(MongoClient client, String database, ReadGrant grant, MongoSourceLineage source,
                                  SourceOccurrenceAccess scored, DeviceAssertionVerifier verifier, DevicePseudonyms pseudonyms,
                                  Clock clock, Runnable afterSnapshotRead, Runnable afterHistoricalGrantVerification) {
        this(client, database, grant, source, scored, verifier, pseudonyms, clock, afterSnapshotRead,
                afterHistoricalGrantVerification, () -> { });
    }

    PrivateDeviceDiagnosticReader(MongoClient client, String database, ReadGrant grant, MongoSourceLineage source,
                                  SourceOccurrenceAccess scored, DeviceAssertionVerifier verifier, DevicePseudonyms pseudonyms,
                                  Clock clock, Runnable afterSnapshotRead, Runnable afterHistoricalGrantVerification,
                                  Runnable afterHistoricalCoverageVerification) {
        this.client = Objects.requireNonNull(client); this.grant = Objects.requireNonNull(grant); this.source = Objects.requireNonNull(source);
        this.scored = Objects.requireNonNull(scored); this.verifier = Objects.requireNonNull(verifier); this.pseudonyms = Objects.requireNonNull(pseudonyms);
        this.clock = Objects.requireNonNull(clock); this.afterSnapshotRead = Objects.requireNonNull(afterSnapshotRead);
        this.afterHistoricalGrantVerification = Objects.requireNonNull(afterHistoricalGrantVerification);
        this.afterHistoricalCoverageVerification = Objects.requireNonNull(afterHistoricalCoverageVerification);
        this.epoch = new DeviceEpoch(grant.scope().sourceIssuer(), grant.scope().sourceEpoch());
        if (database == null || !database.matches("[a-z][a-z0-9_]{0,63}") || pseudonyms.version() != grant.pseudonymVersion()) { throw unauthorized(); }
        db = client.getDatabase(database).withReadConcern(ReadConcern.MAJORITY).withReadPreference(ReadPreference.primary());
        Long timeout = db.getTimeout(TimeUnit.MILLISECONDS);
        if (timeout == null || timeout <= 0 || timeout > 5_000) { throw unauthorized(); }
        requireReadOnlyPrincipal();
    }

    Result evaluate(FactReference fact, SourceOccurrenceAccess.Reference reference) {
        if (fact == null || reference == null) { return Result.of(Outcome.DENIED); }
        long started = System.nanoTime();
        try {
            SourceLineage authorization = source.enrichedBinding(fact.receiptId(), fact.rawCommitment(), fact.enrichedCommitment());
            if (authorization.state() != SourceLineage.State.VERIFIED) {
                return Result.of(authorization.state() == SourceLineage.State.UNAVAILABLE ? Outcome.UNAVAILABLE : Outcome.DENIED);
            }
            if (!authorizedSource(authorization.source())) { return Result.of(Outcome.DENIED); }
            SourceScoredOccurrence occurrence = scored.find(authorization, reference).orElse(null);
            if (!exactScore(occurrence, authorization, reference)) { return Result.of(Outcome.DENIED); }
            String institution = pseudonyms.institution(occurrence.source().institution());
            String sourceCode = DevicePseudonyms.sourceCode(occurrence.source().source());
            String eventKey = pseudonyms.event(occurrence.source().institution(), occurrence.source().sourceEventId());
            String id = epoch.eventId(institution, sourceCode, eventKey);
            View view;
            try (ClientSession session = client.startSession()) {
                session.startTransaction(TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT).readPreference(ReadPreference.primary()).build());
                view = read(session, fact, occurrence, id, institution, sourceCode, eventKey, started);
                session.commitTransaction(); // Read-only transaction; no Device/Source write method is available here.
            }
            if (view.outcome != null) { return Result.of(view.outcome); }
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(5)
                    || !currentHistorySources(view.history, view.historyProvenances, started)
                    || !current(view.row, fact, authorization) || !currentCoverage(view.coverage)) { return Result.of(Outcome.UNAVAILABLE); }
            var evaluation = policy.evaluate(view.evidence);
            afterSnapshotRead.run(); // Deterministic race seam; production constructor supplies a no-op.
            if (!stillCurrent(view, occurrence, id, institution, sourceCode, started)) {
                return Result.of(Outcome.UNAVAILABLE);
            }
            SourceLineage after = source.enrichedBinding(fact.receiptId(), fact.rawCommitment(), fact.enrichedCommitment());
            if (after.state() != SourceLineage.State.VERIFIED || !after.source().equals(authorization.source())
                    || !exactScore(scored.find(after, reference).orElse(null), after, reference)
                    || System.nanoTime() - started > TimeUnit.SECONDS.toNanos(5)) { return Result.of(Outcome.UNAVAILABLE); }
            return new Result(evaluation.status() == com.frauddetection.common.events.engine.FraudEngineStatus.UNAVAILABLE
                    ? Outcome.UNAVAILABLE : Outcome.EVALUATED, evaluation);
        } catch (RuntimeException unavailable) { return Result.of(Outcome.UNAVAILABLE); } // Do not propagate Mongo/proof/identity details.
    }

    private View read(ClientSession session, FactReference fact, SourceScoredOccurrence score, String id, String institution,
                      String sourceCode, String eventKey, long started) {
        Document row = db.getCollection("device_occurrences").find(session, eq("_id", id)).first();
        Document candidate = db.getCollection("device_observation_candidates").find(session, eq("_id", id)).first();
        if (row == null) { return View.failed(candidate != null && "STAGED".equals(candidate.getString("status")) ? Outcome.PENDING : Outcome.UNAVAILABLE); }
        if (!epoch.matches(row) || !institution.equals(row.getString("institution")) || !sourceCode.equals(row.getString("source"))
                || !eventKey.equals(row.getString("sourceEvent")) || !grant.customerToken().equals(row.getString("customer"))) { return View.failed(Outcome.DENIED); }
        if (candidate == null || !"COMMITTED".equals(candidate.getString("status"))
                || !Objects.equals(candidate.getString("provenanceCommitment"), row.getString("provenanceCommitment"))
                || !DeviceRecordIntegrity.candidateMatches(pseudonyms, candidate)
                || !DeviceRecordIntegrity.candidateStateMatches(pseudonyms, candidate)
                || !Objects.equals(candidate.getString("candidateIntegrityCommitment"), row.getString("candidateIntegrityCommitment"))) {
            return View.failed(Outcome.UNAVAILABLE);
        }
        if (!row.containsKey("scoredBinding")) { return View.failed(Outcome.PENDING); }
        DeviceSnapshotVersion.require(row);
        if (!DeviceRecordIntegrity.candidateMatches(pseudonyms, row)
                || !DeviceRecordIntegrity.membershipMatches(pseudonyms, row)
                || !DeviceRecordIntegrity.snapshotMatches(pseudonyms, row)) { return View.failed(Outcome.UNAVAILABLE); }
        if (!DeviceScoredBinding.matches(row, score)) { return View.failed(Outcome.DENIED); }
        var provenance = DeviceObservationStore.Provenance.from(row);
        if (!provenance.receiptId().equals(fact.receiptId()) || !provenance.rawCommitment().equals(fact.rawCommitment())
                || !provenance.enrichedCommitment().equals(fact.enrichedCommitment())) { return View.failed(Outcome.DENIED); }
        DeviceRiskEvidence evidence = MongoDeviceObservationStore.decode(row);
        if (!Objects.equals(evidence.occurrenceKey(), row.getString("transaction")) || !evidence.institutionToken().equals(institution)
                || !Objects.equals(evidence.customerToken(), row.getString("customer")) || !Objects.equals(evidence.deviceToken(), row.getString("device"))
                || evidence.keyVersion() != grant.pseudonymVersion() || evidence.keyVersion() != row.getInteger("keyVersion")
                || evidence.source().quality() != DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION
                || !sourceCode.equals(evidence.source().authorityCode()) || !eventKey.equals(evidence.source().sourceEventKey())
                || !evidence.eventTime().equals(score.source().eventTime()) || !evidence.eventTime().equals(row.getDate("eventTime").toInstant())
                || !evidence.eventTime().equals(evidence.asOf()) || !provenance.proofTime().equals(evidence.eventTime())
                || !evidence.windowStart().equals(evidence.asOf().minus(DeviceRiskSignalPolicy.HISTORY_WINDOW))) { return View.failed(Outcome.DENIED); }
        if (!DeviceObservationStore.CoverageAuthority.from(row).proofTime().equals(evidence.coverageThrough())) { return View.failed(Outcome.DENIED); }
        Document progress = db.getCollection("device_source_coverage").find(session, eq("_id", epoch.scopeId(institution, sourceCode))).first();
        if (progress == null || !epoch.matches(progress) || Boolean.TRUE.equals(progress.getBoolean("invalidated"))
                || !Objects.equals(progress.getInteger("keyVersion"), row.getInteger("keyVersion"))
                || !Objects.equals(progress.getString("populationCommitment"), row.getString("populationCommitment"))
                || progress.getDate("watermark").toInstant().isBefore(evidence.asOf()) || !currentCoverage(progress)
                || activeRevocation(session, institution, sourceCode, row.getInteger("keyVersion"), row.getString("device"))
                || !retained(row, evidence)) { return View.failed(Outcome.UNAVAILABLE); }
        // A once-complete snapshot cannot regain authority after continuity was lost or narrowed.
        if (evidence.priorDevice().state() == DeviceRiskEvidence.State.AVAILABLE
                && (progress.getDate("coverageStart").toInstant().isAfter(evidence.windowStart())
                || progress.getDate("continuousSince").toInstant().isAfter(evidence.windowStart()))) { return View.failed(Outcome.UNAVAILABLE); }
        History history = history(session, evidence, institution, sourceCode);
        List<DeviceObservationStore.Provenance> historyProvenances = new ArrayList<>(history.observations().size());
        if (!history.withinLimit()
                || !committedCandidates(List.of(candidate))
                || !historyMembershipMatches(row, evidence, history.observations(), institution, sourceCode)
                || !historyRowsMatch(history.observations(), history.occurrences(), history.candidates(), historyProvenances)) {
            return View.failed(Outcome.UNAVAILABLE);
        }
        return new View(null, row, candidate, progress, evidence, history.observations(), historyProvenances);
    }

    private History history(ClientSession session, DeviceRiskEvidence evidence, String institution, String sourceCode) {
        Bson observationFilter = and(epoch.filter(institution, sourceCode), eq("keyVersion", evidence.keyVersion()),
                gte("eventTime", Date.from(evidence.windowStart())), lt("eventTime", Date.from(evidence.asOf())),
                gt("expireAt", Date.from(clock.instant())), or(eq("customer", evidence.customerToken()), eq("device", evidence.deviceToken())));
        Bson candidateFilter = and(epoch.filter(institution, sourceCode), eq("keyVersion", evidence.keyVersion()),
                gte("eventTime", Date.from(evidence.windowStart())), lt("eventTime", Date.from(evidence.asOf())),
                or(eq("customer", evidence.customerToken()), eq("device", evidence.deviceToken())));
        List<Document> observations = db.getCollection("device_observations").find(session, observationFilter)
                .projection(OBSERVATION_HISTORY_PROJECTION)
                .sort(new Document("eventTime", 1).append("sourceEvent", 1)).limit(MAX_VERIFIED_HISTORY_MEMBERS + 1)
                .batchSize(MAX_VERIFIED_HISTORY_MEMBERS + 1).into(new ArrayList<>());
        List<Document> occurrences = db.getCollection("device_occurrences").find(session, observationFilter)
                .projection(OCCURRENCE_HISTORY_PROJECTION)
                .sort(new Document("eventTime", 1).append("sourceEvent", 1)).limit(MAX_VERIFIED_HISTORY_MEMBERS + 1)
                .batchSize(MAX_VERIFIED_HISTORY_MEMBERS + 1).into(new ArrayList<>());
        List<Document> candidates = db.getCollection("device_observation_candidates").find(session, candidateFilter)
                .projection(CANDIDATE_HISTORY_PROJECTION)
                .sort(new Document("eventTime", 1).append("sourceEvent", 1)).limit(MAX_VERIFIED_HISTORY_MEMBERS + 1)
                .batchSize(MAX_VERIFIED_HISTORY_MEMBERS + 1).into(new ArrayList<>());
        return new History(observations, occurrences, candidates,
                withinHistoryLimit(observations.size()) && withinHistoryLimit(occurrences.size())
                        && withinHistoryLimit(candidates.size()));
    }

    static boolean withinHistoryLimit(int size) { return size >= 0 && size <= MAX_VERIFIED_HISTORY_MEMBERS; }

    private boolean current(Document row, FactReference fact, SourceLineage authorization) {
        var p = DeviceObservationStore.Provenance.from(row);
        return p.receiptId().equals(fact.receiptId()) && p.rawCommitment().equals(fact.rawCommitment()) && p.enrichedCommitment().equals(fact.enrichedCommitment())
                && DeviceRecordIntegrity.snapshotMatches(pseudonyms, row)
                && verifier.currentStoredObservation(p, authorization, row.getInteger("keyVersion"));
    }
    private boolean currentCoverage(Document row) {
        return (!row.containsKey("coverageGeneration") || DeviceRecordIntegrity.coverageMatches(pseudonyms, row))
                && verifier.currentStoredCoverage(DeviceObservationStore.CoverageAuthority.from(row), epoch, row.getString("institution"), row.getString("source"),
                row.getString("populationCommitment"), row.getInteger("keyVersion"), row.getDate("coverageStart").toInstant(), pseudonyms);
    }
    private boolean committedCandidates(List<Document> candidates) {
        Instant now = clock.instant();
        for (Document candidate : candidates) {
            Date terminalExpiry = candidate.getDate("terminalExpireAt");
            if (!epoch.matches(candidate) || !"COMMITTED".equals(candidate.getString("status"))
                    || terminalExpiry == null || !terminalExpiry.toInstant().isAfter(now)
                    || !DeviceRecordIntegrity.candidateMatches(pseudonyms, candidate)
                    || !DeviceRecordIntegrity.candidateStateMatches(pseudonyms, candidate)) { return false; }
        }
        return true;
    }
    private boolean historyMembershipMatches(Document row, DeviceRiskEvidence evidence, List<Document> history,
                                             String institution, String sourceCode) {
        boolean hasSupportedHistory = evidence.priorDevice().state() == DeviceRiskEvidence.State.AVAILABLE
                || evidence.otherCustomers().state() == DeviceRiskEvidence.State.AVAILABLE
                || evidence.switches().state() == DeviceRiskEvidence.State.AVAILABLE;
        if (!hasSupportedHistory) { return true; }
        String stored = row.getString("historyMembershipCommitment");
        Integer storedCount = row.getInteger("historyMembershipCount");
        if (stored == null || storedCount == null) { return false; }
        DeviceHistoryMembershipCommitment.Value expected = DeviceHistoryMembershipCommitment.compute(pseudonyms, epoch,
                institution, sourceCode, evidence.customerToken(), evidence.deviceToken(), evidence.keyVersion(),
                evidence.windowStart(), evidence.asOf(), history);
        return storedCount == expected.size() && java.security.MessageDigest.isEqual(
                stored.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expected.commitment().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    private boolean historyRowsMatch(List<Document> rows, List<Document> occurrences, List<Document> candidates,
                                     List<DeviceObservationStore.Provenance> provenances) {
        // The three history queries use the same eventTime/sourceEvent ordering; any positional mismatch fails closed.
        if (rows.size() != occurrences.size() || rows.size() != candidates.size()) { return false; }
        Instant now = clock.instant();
        for (int index = 0; index < rows.size(); index++) {
            Document row = rows.get(index);
            if (!epoch.matches(row)) { return false; }
            String rowId = row.getString("_id");
            Document occurrence = occurrences.get(index);
            Document candidate = candidates.get(index);
            if (rowId == null || !rowId.equals(occurrence.getString("_id")) || !rowId.equals(candidate.getString("_id"))) { return false; }
            Date terminalExpiry = candidate.getDate("terminalExpireAt");
            if (!epoch.matches(candidate) || terminalExpiry == null || !terminalExpiry.toInstant().isAfter(now)
                    || !epoch.matches(occurrence) || !DeviceRecordIntegrity.candidateInputsMatch(occurrence, candidate)
                    || !Objects.equals(row.get("provenance"), occurrence.get("provenance"))
                    || !Objects.equals(occurrence.get("provenance"), candidate.get("provenance"))
                    || !Objects.equals(row.getString("provenanceCommitment"), occurrence.getString("provenanceCommitment"))) { return false; }
            var provenance = DeviceObservationStore.Provenance.from(row);
            provenances.add(provenance);
            var coverageAuthority = DeviceObservationStore.CoverageAuthority.from(occurrence);
            DeviceSnapshotVersion.require(occurrence, provenance, coverageAuthority);
            if (!DeviceRecordIntegrity.membershipMatches(pseudonyms, row, provenance)
                    || !DeviceRecordIntegrity.membershipMatches(pseudonyms, occurrence, provenance)
                    || !DeviceRecordIntegrity.candidateMatches(pseudonyms, occurrence, provenance)
                    || !DeviceRecordIntegrity.snapshotMatches(pseudonyms, occurrence)
                    || !DeviceRecordIntegrity.candidateStateMatches(pseudonyms, candidate)
                    || !"COMMITTED".equals(candidate.getString("status"))
                    || !Objects.equals(candidate.getString("candidateIntegrityCommitment"), occurrence.getString("candidateIntegrityCommitment"))
                    || !Objects.equals(row.getString("institution"), occurrence.getString("institution"))
                    || !Objects.equals(row.getString("source"), occurrence.getString("source"))
                    || !Objects.equals(row.getString("sourceEvent"), occurrence.getString("sourceEvent"))
                    || !Objects.equals(row.getString("customer"), occurrence.getString("customer"))
                    || !Objects.equals(row.getString("device"), occurrence.getString("device"))
                    || !Objects.equals(row.getInteger("keyVersion"), occurrence.getInteger("keyVersion"))
                    || !Objects.equals(row.getDate("eventTime"), occurrence.getDate("eventTime"))) { return false; }
            if (!verifier.currentStoredCoverage(coverageAuthority, epoch,
                    occurrence.getString("institution"), occurrence.getString("source"), occurrence.getString("populationCommitment"),
                    occurrence.getInteger("keyVersion"), occurrence.getDate("coverageStart").toInstant(), pseudonyms)) { return false; }
            afterHistoricalCoverageVerification.run();
        }
        return true;
    }
    private boolean currentHistorySources(List<Document> rows, List<DeviceObservationStore.Provenance> provenances, long started) {
        if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(5) || rows.size() > MAX_VERIFIED_HISTORY_MEMBERS
                || rows.size() != provenances.size()) { return false; }
        List<MongoSourceLineage.EnrichedBinding> bindings = new ArrayList<>(rows.size());
        for (DeviceObservationStore.Provenance provenance : provenances) {
            bindings.add(new MongoSourceLineage.EnrichedBinding(provenance.receiptId(), provenance.rawCommitment(),
                    provenance.enrichedCommitment()));
        }
        List<SourceLineage> lineages = source.enrichedBindings(bindings);
        if (lineages.size() != rows.size() || System.nanoTime() - started > TimeUnit.SECONDS.toNanos(5)) { return false; }
        for (int index = 0; index < rows.size(); index++) {
            Document row = rows.get(index);
            SourceLineage lineage = lineages.get(index);
            var provenance = provenances.get(index);
            if (lineage.state() != SourceLineage.State.VERIFIED || !authorizedSource(lineage.source())
                    || !pseudonyms.event(lineage.source().institution(), lineage.source().sourceEventId()).equals(row.getString("sourceEvent"))
                    || !lineage.source().eventTime().equals(row.getDate("eventTime").toInstant())
                    || !verifier.currentStoredObservation(provenance, lineage, row.getInteger("keyVersion"))) { return false; }
            afterHistoricalGrantVerification.run();
        }
        return System.nanoTime() - started <= TimeUnit.SECONDS.toNanos(5);
    }
    private boolean stillCurrent(View view, SourceScoredOccurrence scored, String id, String institution, String sourceCode, long started) {
        if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(5)) { return false; }
        try (ClientSession session = client.startSession()) {
            session.startTransaction(TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT).readPreference(ReadPreference.primary()).build());
            Document row = db.getCollection("device_occurrences").find(session, eq("_id", id)).first();
            Document candidate = db.getCollection("device_observation_candidates").find(session, eq("_id", id)).first();
            Document coverage = db.getCollection("device_source_coverage").find(session,
                    eq("_id", epoch.scopeId(institution, sourceCode))).first();
            DeviceRiskEvidence evidence = view.evidence;
            History history = history(session, evidence, institution, sourceCode);
            List<DeviceObservationStore.Provenance> historyProvenances = new ArrayList<>(history.observations().size());
            boolean current = row != null && candidate != null && coverage != null && history.withinLimit()
                    && historyRowsMatch(history.observations(), history.occurrences(), history.candidates(), historyProvenances)
                    && historyMembershipMatches(row, evidence, history.observations(), institution, sourceCode)
                    && Objects.equals(row.getString("stageACommitment"), view.row.getString("stageACommitment"))
                    && Objects.equals(row.get("scoredBinding", Document.class).getString("commitment"),
                    view.row.get("scoredBinding", Document.class).getString("commitment"))
                    && Objects.equals(row.getString("evidenceCommitment"), view.row.getString("evidenceCommitment"))
                    && Objects.equals(row.getString("provenanceCommitment"), view.row.getString("provenanceCommitment"))
                    && Objects.equals(coverage.get("revision"), view.coverage.get("revision"))
                    && Objects.equals(coverage.get("coverageGeneration"), view.coverage.get("coverageGeneration"))
                    && !Boolean.TRUE.equals(coverage.getBoolean("invalidated"))
                    && DeviceSnapshotVersion.commitment(row).equals(row.getString("stageACommitment"))
                    && DeviceRecordIntegrity.candidateMatches(pseudonyms, row)
                    && DeviceRecordIntegrity.membershipMatches(pseudonyms, row)
                    && DeviceRecordIntegrity.snapshotMatches(pseudonyms, row)
                    && DeviceRecordIntegrity.candidateMatches(pseudonyms, candidate)
                    && DeviceRecordIntegrity.candidateStateMatches(pseudonyms, candidate)
                    && "COMMITTED".equals(candidate.getString("status"))
                    && committedCandidates(List.of(candidate))
                    && Objects.equals(candidate.getString("candidateStateIntegrityCommitment"),
                    view.candidate.getString("candidateStateIntegrityCommitment"))
                    && Objects.equals(candidate.getString("candidateIntegrityCommitment"), row.getString("candidateIntegrityCommitment"))
                    && DeviceScoredBinding.matches(row, scored) && currentCoverage(coverage)
                    && retained(row, evidence)
                    && !activeRevocation(session, institution, sourceCode, row.getInteger("keyVersion"), row.getString("device"));
            session.commitTransaction();
            return current && currentHistorySources(history.observations(), historyProvenances, started)
                    && System.nanoTime() - started <= TimeUnit.SECONDS.toNanos(5);
        } catch (RuntimeException unavailable) { return false; }
    }
    private boolean retained(Document row, DeviceRiskEvidence evidence) {
        return row.getDate("expireAt").toInstant().isAfter(clock.instant()) && !clock.instant().isAfter(evidence.asOf().plus(REPAIR_HORIZON));
    }
    private boolean activeRevocation(ClientSession session, String institution, String sourceCode, int version, String device) {
        var filter = and(eq("_id", epoch.scopeId(institution, sourceCode) + ':' + version + ':' + device), gt("expireAt", Date.from(clock.instant())));
        var collection = db.getCollection("device_operational_revocation_tombstones");
        return (session == null ? collection.find(filter) : collection.find(session, filter)).first() != null;
    }
    private boolean authorizedSource(SourceScope scope) {
        return scope != null && DeviceAssertionVerifier.TRANSACTION_PURPOSE.equals(scope.purpose()) && grant.sourceAudience().equals(scope.audience())
                && grant.scope().matches(scope.issuer(), scope.institution(), scope.source(), scope.sourceEpoch());
    }
    private boolean exactScore(SourceScoredOccurrence value, SourceLineage authorization, SourceOccurrenceAccess.Reference reference) {
        return value != null && MongoSourceOccurrenceStore.INITIAL_RUN.equals(value.runId()) && value.source().equals(authorization.source())
                && value.receiptId().equals(authorization.receiptId()) && value.enrichedCommitment().equals(authorization.eventCommitment())
                && value.scoredEventId().equals(reference.eventId()) && value.scoredCreatedAt().equals(reference.createdAt())
                && value.scoredFingerprint().equals(reference.fingerprint());
    }
    private void requireReadOnlyPrincipal() {
        try {
            Document auth = db.runCommand(new Document("connectionStatus", 1).append("showPrivileges", true)).get("authInfo", Document.class);
            List<Document> users = auth.getList("authenticatedUsers", Document.class);
            if (users.size() != 1 || !grant.principal().equals(users.getFirst().getString("user"))) { throw unauthorized(); }
            Set<String> readable = new HashSet<>();
            for (Document privilege : auth.getList("authenticatedUserPrivileges", Document.class)) {
                Document resource = privilege.get("resource", Document.class); List<String> actions = privilege.getList("actions", String.class);
                if (resource == null || !db.getName().equals(resource.getString("db")) || actions == null
                        || !Set.of("find", "listIndexes", "listCollections").containsAll(actions)) { throw unauthorized(); }
                String collection = resource.getString("collection");
                if (collection == null || collection.isEmpty() && !actions.stream().allMatch("listCollections"::equals)
                        || !collection.isEmpty() && !COLLECTIONS.contains(collection)) { throw unauthorized(); }
                if (actions.contains("find")) { readable.add(collection); }
            }
            if (!readable.containsAll(COLLECTIONS)) { throw unauthorized(); }
        } catch (RuntimeException invalid) { throw unauthorized(); }
    }
    private static SecurityException unauthorized() { return new SecurityException("DEVICE_PRIVATE_READ_NOT_AUTHORIZED"); }

    enum Outcome { EVALUATED, UNAVAILABLE, PENDING, DENIED }
    @JsonIgnoreType
    record FactReference(String receiptId, String rawCommitment, String enrichedCommitment) {
        FactReference { DeviceAssertions.token(receiptId); DeviceAssertions.token(rawCommitment); DeviceAssertions.token(enrichedCommitment); }
        @Override public String toString() { return "DeviceFactReference[redacted]"; }
    }
    @JsonIgnoreType
    record ReadGrant(String principal, String purpose, String audience, DeviceAssertions.Scope scope, String sourceAudience,
                     String customerToken, int pseudonymVersion) {
        ReadGrant {
            DeviceAssertions.reference(principal); Objects.requireNonNull(scope); DeviceAssertions.reference(sourceAudience); DeviceAssertions.token(customerToken);
            if (!PURPOSE.equals(purpose) || !AUDIENCE.equals(audience) || pseudonymVersion < 1 || pseudonymVersion > 65_535) { throw unauthorized(); }
        }
        @Override public String toString() { return "DevicePrivateReadGrant[redacted]"; }
    }
    @JsonIgnoreType
    record Result(Outcome outcome, DeviceRiskSignalPolicy.Evaluation evaluation) {
        static Result of(Outcome outcome) { return new Result(outcome, null); }
        @Override public String toString() { return "DevicePrivateDiagnostic[redacted]"; }
    }
    private record History(List<Document> observations, List<Document> occurrences, List<Document> candidates, boolean withinLimit) { }
    private record View(Outcome outcome, Document row, Document candidate, Document coverage,
                        DeviceRiskEvidence evidence, List<Document> history,
                        List<DeviceObservationStore.Provenance> historyProvenances) {
        static View failed(Outcome outcome) { return new View(outcome, null, null, null, null, List.of(), List.of()); }
    }
}
