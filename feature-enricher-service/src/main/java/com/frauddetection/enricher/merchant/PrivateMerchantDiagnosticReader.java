package com.frauddetection.enricher.merchant;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.SourceOccurrenceAccess;
import com.frauddetection.common.events.source.SourceScoredOccurrence;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Availability;
import com.frauddetection.enricher.merchant.MerchantObservationStore.DiagnosticSnapshot;
import com.frauddetection.enricher.merchant.MerchantObservationStore.DiagnosticState;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Limits;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Scope;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Snapshot;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Private, read-only Stage C. It is deliberately not registered as a Spring bean or scoring provider. */
final class PrivateMerchantDiagnosticReader {
    static final String PURPOSE = "MERCHANT_PRIVATE_DIAGNOSTIC_READ";
    static final String AUDIENCE = "merchant-diagnostics-private";
    static final int MAX_HISTORY_ROWS = 1_000;
    private static final long VALIDATION_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final Set<String> COLLECTIONS = Set.copyOf(MongoMerchantObservationSchema.COLLECTIONS);

    private final MongoDatabase database;
    private final ReadGrant grant;
    private final ReadGrantVerifier grantVerifier;
    private final VerifiedMerchantAdmission admission;
    private final SourceOccurrenceAccess scored;
    private final Runnable afterSnapshotRead;
    private final LongSupplier nanoTime;

    PrivateMerchantDiagnosticReader(MongoClient ledgerClient, String databaseName, ReadGrant grant,
                                    ReadGrantVerifier grantVerifier, MongoSourceLineage source,
                                    SourceOccurrenceAccess scored, SourceVerificationProperties origins,
                                    List<MerchantAssertionTrustPolicy.Grant> merchantGrants,
                                    MerchantPseudonyms pseudonyms, Clock clock, Limits limits) {
        this(ledgerClient, databaseName, grant, grantVerifier, source, scored, origins, merchantGrants,
                pseudonyms, clock, limits, () -> { }, System::nanoTime);
    }

    PrivateMerchantDiagnosticReader(MongoClient ledgerClient, String databaseName, ReadGrant grant,
                                    ReadGrantVerifier grantVerifier, MongoSourceLineage source,
                                    SourceOccurrenceAccess scored, SourceVerificationProperties origins,
                                    List<MerchantAssertionTrustPolicy.Grant> merchantGrants,
                                    MerchantPseudonyms pseudonyms, Clock clock, Limits limits,
                                    Runnable afterSnapshotRead, LongSupplier nanoTime) {
        this.grant = Objects.requireNonNull(grant, "MERCHANT_PRIVATE_READ_GRANT_REQUIRED");
        this.grantVerifier = Objects.requireNonNull(grantVerifier, "MERCHANT_PRIVATE_READ_GRANT_VERIFIER_REQUIRED");
        this.scored = Objects.requireNonNull(scored, "MERCHANT_PRIVATE_SCORED_ACCESS_REQUIRED");
        this.afterSnapshotRead = Objects.requireNonNull(afterSnapshotRead);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        Objects.requireNonNull(ledgerClient, "MERCHANT_PRIVATE_READ_CLIENT_REQUIRED");
        Objects.requireNonNull(limits, "MERCHANT_LIMITS_REQUIRED");
        if (databaseName == null || !databaseName.matches("[a-z][a-z0-9_]{0,63}")
                || limits.maxHistoryRows() > grant.maxHistoryRows()) { throw unauthorized(); }
        database = ledgerClient.getDatabase(databaseName).withReadConcern(ReadConcern.MAJORITY)
                .withReadPreference(ReadPreference.primary());
        Long timeout = database.getTimeout(TimeUnit.MILLISECONDS);
        if (timeout == null || timeout <= 0 || timeout > 5_000) { throw unauthorized(); }
        requireReadOnlyPrincipal();
        admission = new VerifiedMerchantAdmission(ledgerClient, databaseName, Objects.requireNonNull(clock), limits,
                Objects.requireNonNull(source), Objects.requireNonNull(origins),
                List.copyOf(Objects.requireNonNull(merchantGrants)), Objects.requireNonNull(pseudonyms));
    }

    Result evaluate(Request request) {
        if (request == null || !currentlyAuthorized()) { return Result.of(Outcome.DENIED); }
        long started = nanoTime.getAsLong();
        VerifiedMerchantAdmission.Ticket ticket;
        try {
            ticket = admission.authorizeRead(request.sourceProof(), request.sourcePayload(), request.observationProof(),
                    request.observationAssertion(), request.raw(), request.enriched());
        } catch (SecurityException denied) {
            return Result.of(Outcome.DENIED);
        } catch (RuntimeException unavailable) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        if (!admission.matchesReadGrant(ticket, grant.scope(), grant.merchantPseudonym())) {
            return Result.of(Outcome.DENIED);
        }
        if (!withinBudget(started)) { return Result.of(Outcome.UNAVAILABLE); }

        SourceScoredOccurrence occurrence;
        try { occurrence = admission.resolveScored(ticket, scored, request.reference()); }
        catch (SecurityException denied) { return Result.of(Outcome.DENIED); }
        catch (RuntimeException unavailable) { return Result.of(Outcome.UNAVAILABLE); }
        if (occurrence == null) { return Result.of(Outcome.PENDING); }
        if (!withinBudget(started)) { return Result.of(Outcome.UNAVAILABLE); }

        DiagnosticSnapshot first;
        try { first = admission.readDiagnostic(ticket, occurrence); }
        catch (RuntimeException unavailable) { return Result.of(Outcome.UNAVAILABLE); }
        if (first.state() == DiagnosticState.PENDING) { return Result.of(Outcome.PENDING); }
        if (first.state() == DiagnosticState.READY && !first.exactScoredBindingPresent()) {
            return Result.of(Outcome.PENDING);
        }
        if (!ready(first)) { return Result.of(Outcome.UNAVAILABLE); }

        try { afterSnapshotRead.run(); }
        catch (RuntimeException unavailable) { return Result.of(Outcome.UNAVAILABLE); }
        if (!withinBudget(started)) { return Result.of(Outcome.UNAVAILABLE); }
        if (!currentlyAuthorized()) { return Result.of(Outcome.DENIED); }

        try {
            admission.recheck(ticket);
            SourceScoredOccurrence current = admission.resolveScored(ticket, scored, request.reference());
            if (!sameOccurrence(occurrence, current)) { return Result.of(Outcome.UNAVAILABLE); }
            DiagnosticSnapshot finalRead = admission.readDiagnostic(ticket, current);
            if (!ready(finalRead) || !finalRead.exactScoredBindingPresent()
                    || !first.snapshot().equals(finalRead.snapshot())) { return Result.of(Outcome.UNAVAILABLE); }
            admission.recheck(ticket);
            SourceScoredOccurrence last = admission.resolveScored(ticket, scored, request.reference());
            if (!currentlyAuthorized()) { return Result.of(Outcome.DENIED); }
            if (!sameOccurrence(current, last) || !withinBudget(started)) {
                return Result.of(Outcome.UNAVAILABLE);
            }
            return evaluated(finalRead.snapshot());
        } catch (RuntimeException unavailable) {
            return Result.of(Outcome.UNAVAILABLE);
        }
    }

    private Result evaluated(Snapshot snapshot) {
        if (snapshot.availability() != Availability.COMPLETE_WINDOW || snapshot.priorCount() == null) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        Claim claim = snapshot.priorCount() == 0
                ? Claim.FIRST_OBSERVED_IN_COMPLETE_RECENT_WINDOW
                : Claim.PREVIOUSLY_OBSERVED_IN_COMPLETE_RECENT_WINDOW;
        return new Result(Outcome.EVALUATED, claim);
    }

    private static boolean ready(DiagnosticSnapshot value) {
        return value != null && value.state() == DiagnosticState.READY
                && value.snapshot() != null && value.snapshot().availability() == Availability.COMPLETE_WINDOW
                && value.snapshot().priorCount() != null && value.exactScoredBindingPresent();
    }

    private boolean currentlyAuthorized() {
        try { return grantVerifier.current(grant); }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean withinBudget(long started) {
        long elapsed = nanoTime.getAsLong() - started;
        return elapsed >= 0 && elapsed <= VALIDATION_BUDGET_NANOS;
    }

    private static boolean sameOccurrence(SourceScoredOccurrence left, SourceScoredOccurrence right) {
        return left != null && right != null && left.source().equals(right.source())
                && left.receiptId().equals(right.receiptId())
                && left.enrichedCommitment().equals(right.enrichedCommitment())
                && left.scoredEventId().equals(right.scoredEventId())
                && left.scoredCreatedAt().equals(right.scoredCreatedAt())
                && left.scoredFingerprint().equals(right.scoredFingerprint())
                && left.runId().equals(right.runId());
    }

    private void requireReadOnlyPrincipal() {
        try {
            Document auth = database.runCommand(new Document("connectionStatus", 1).append("showPrivileges", true))
                    .get("authInfo", Document.class);
            List<Document> users = auth == null ? null : auth.getList("authenticatedUsers", Document.class);
            List<Document> privileges = auth == null ? null : auth.getList("authenticatedUserPrivileges", Document.class);
            if (users == null || users.size() != 1 || !grant.principal().equals(users.getFirst().getString("user"))
                    || privileges == null) { throw unauthorized(); }
            Map<String, Set<String>> allowed = new HashMap<>();
            allowed.put("", Set.of("listCollections"));
            for (String collection : COLLECTIONS) { allowed.put(collection, Set.of("find", "listIndexes")); }
            Map<String, Set<String>> observed = new HashMap<>();
            for (Document privilege : privileges) {
                Document resource = privilege.get("resource", Document.class);
                List<String> actions = privilege.getList("actions", String.class);
                String collection = resource == null ? null : resource.getString("collection");
                if (resource == null || !database.getName().equals(resource.getString("db")) || collection == null
                        || actions == null || actions.isEmpty() || !allowed.containsKey(collection)
                        || !allowed.get(collection).containsAll(actions)) { throw unauthorized(); }
                observed.computeIfAbsent(collection, ignored -> new HashSet<>()).addAll(actions);
            }
            if (!observed.equals(allowed)) { throw unauthorized(); }
        } catch (RuntimeException invalid) { throw unauthorized(); }
    }

    private static SecurityException unauthorized() {
        return new SecurityException("MERCHANT_PRIVATE_READ_NOT_AUTHORIZED");
    }

    @FunctionalInterface
    interface ReadGrantVerifier { boolean current(ReadGrant grant); }

    enum Outcome { EVALUATED, UNAVAILABLE, PENDING, DENIED }

    enum Claim { FIRST_OBSERVED_IN_COMPLETE_RECENT_WINDOW, PREVIOUSLY_OBSERVED_IN_COMPLETE_RECENT_WINDOW }

    @JsonIgnoreType
    record ReadGrant(String principal, String purpose, String audience, Scope scope,
                     String merchantPseudonym, int maxHistoryRows) {
        ReadGrant {
            if (principal == null || !principal.matches("[A-Za-z0-9._:-]{1,128}")
                    || !PURPOSE.equals(purpose) || !AUDIENCE.equals(audience) || scope == null
                    || merchantPseudonym == null || !merchantPseudonym.matches("[0-9a-f]{64}")
                    || maxHistoryRows < 1 || maxHistoryRows > MAX_HISTORY_ROWS) { throw unauthorized(); }
        }
        @Override public String toString() { return "MerchantPrivateReadGrant[redacted]"; }
    }

    @JsonIgnoreType
    static final class Request {
        private static final int MAX_SOURCE_BYTES = 262_144;
        private static final int MAX_PROOF_BYTES = 16_384;
        private final byte[] sourceProof;
        private final byte[] sourcePayload;
        private final byte[] observationProof;
        private final byte[] observationAssertion;
        private final TransactionRawEvent raw;
        private final TransactionEnrichedEvent enriched;
        private final SourceOccurrenceAccess.Reference reference;

        Request(byte[] sourceProof, byte[] sourcePayload, byte[] observationProof, byte[] observationAssertion,
                TransactionRawEvent raw, TransactionEnrichedEvent enriched, SourceOccurrenceAccess.Reference reference) {
            this.sourceProof = boundedCopy(sourceProof, MAX_PROOF_BYTES);
            this.sourcePayload = boundedCopy(sourcePayload, MAX_SOURCE_BYTES);
            this.observationProof = boundedCopy(observationProof, MAX_PROOF_BYTES);
            this.observationAssertion = boundedCopy(observationAssertion, MAX_SOURCE_BYTES);
            this.raw = Objects.requireNonNull(raw);
            this.enriched = Objects.requireNonNull(enriched);
            this.reference = Objects.requireNonNull(reference);
        }

        private static byte[] boundedCopy(byte[] value, int max) {
            if (value == null || value.length == 0 || value.length > max) { throw new IllegalArgumentException("MERCHANT_PRIVATE_READ_REQUEST_INVALID"); }
            return value.clone();
        }
        byte[] sourceProof() { return sourceProof.clone(); }
        byte[] sourcePayload() { return sourcePayload.clone(); }
        byte[] observationProof() { return observationProof.clone(); }
        byte[] observationAssertion() { return observationAssertion.clone(); }
        TransactionRawEvent raw() { return raw; }
        TransactionEnrichedEvent enriched() { return enriched; }
        SourceOccurrenceAccess.Reference reference() { return reference; }
        @Override public String toString() { return "MerchantPrivateReadRequest[redacted]"; }
    }

    @JsonIgnoreType
    record Result(Outcome outcome, Claim claim) {
        Result {
            Objects.requireNonNull(outcome);
            if ((outcome == Outcome.EVALUATED) != (claim != null)) { throw new IllegalArgumentException("MERCHANT_PRIVATE_RESULT_STATE"); }
        }
        static Result of(Outcome outcome) { return new Result(outcome, null); }
        @Override public String toString() { return "MerchantPrivateDiagnostic[redacted]"; }
    }
}
