package com.frauddetection.enricher.merchant;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.MongoSourceLineage.EnrichedBinding;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceReceiptIdentity;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceOccurrenceAccess;
import com.frauddetection.common.events.source.SourceScoredOccurrence;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.mongodb.client.MongoClient;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CommitResult;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CoverageSeal;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CoverageGeneration;
import com.frauddetection.enricher.merchant.MerchantObservationStore.DiagnosticSnapshot;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Limits;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Scope;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Private Stage A vertical. Deliberately package-private and not registered as a Spring component. */
final class VerifiedMerchantAdmission {
    private final MongoSourceLineage source;
    private final SourceSignatureVerifier originVerifier;
    private final List<String> originAudiences;
    private final MerchantAssertionVerifier verifier;
    private final MerchantPseudonyms pseudonyms;
    private final MongoMerchantObservationStore store;
    private final Admission admission = new Admission();
    private final ThreadLocal<AuthorityContext> active = new ThreadLocal<>();

    VerifiedMerchantAdmission(MongoClient client, String database, Clock clock, Limits limits,
                              MongoSourceLineage source,
                              SourceVerificationProperties origins,
                              List<MerchantAssertionTrustPolicy.Grant> grants, MerchantPseudonyms pseudonyms) {
        this.source = Objects.requireNonNull(source, "MERCHANT_SOURCE_LINEAGE_REQUIRED");
        Objects.requireNonNull(origins, "MERCHANT_SOURCE_POLICY_REQUIRED");
        this.originVerifier = new SourceSignatureVerifier(origins, Objects.requireNonNull(clock));
        this.originAudiences = origins.keys().stream()
                .filter(key -> "TRANSACTION_SOURCE_ATTESTATION".equals(key.purpose()))
                .map(SourceVerificationProperties.TrustedKey::audience).distinct().toList();
        this.verifier = new MerchantAssertionVerifier(origins, grants, clock);
        this.pseudonyms = Objects.requireNonNull(pseudonyms, "MERCHANT_PSEUDONYMS_REQUIRED");
        this.store = new MongoMerchantObservationStore(client, database, clock,
                Objects.requireNonNull(limits, "MERCHANT_LIMITS_REQUIRED"), admission);
    }

    Ticket stage(byte[] sourceProof, byte[] originalSourcePayload, byte[] observationProof,
                 byte[] originalObservationAssertion, TransactionRawEvent raw, TransactionEnrichedEvent enriched) {
        VerifiedInput verified = verifyInput(sourceProof, originalSourcePayload, observationProof,
                originalObservationAssertion, raw, enriched);
        AuthorityContext context = new AuthorityContext(verified.input, verified.origin, verified.raw, verified.enriched,
                verified.observation, null, null);
        MerchantObservationStore.StageResult status = withContext(context, () -> store.stage(verified.input));
        return new Ticket(this, verified.origin, verified.raw, verified.enriched, verified.observation, verified.input, status);
    }

    /** Reconstructs the exact authority context for a private read without staging or writing any ledger row. */
    Ticket authorizeRead(byte[] sourceProof, byte[] originalSourcePayload, byte[] observationProof,
                         byte[] originalObservationAssertion, TransactionRawEvent raw, TransactionEnrichedEvent enriched) {
        VerifiedInput verified = verifyInput(sourceProof, originalSourcePayload, observationProof,
                originalObservationAssertion, raw, enriched);
        return new Ticket(this, verified.origin, verified.raw, verified.enriched, verified.observation,
                verified.input, MerchantObservationStore.StageResult.EXISTING);
    }

    private VerifiedInput verifyInput(byte[] sourceProof, byte[] originalSourcePayload, byte[] observationProof,
                                      byte[] originalObservationAssertion, TransactionRawEvent raw,
                                      TransactionEnrichedEvent enriched) {
        SourceSignatureVerifier.Verified origin = verifiedOrigin(sourceProof, originalSourcePayload);
        SourceLineage rawLineage = source.raw(raw);
        SourceLineage enrichedLineage = source.enriched(enriched);
        requireLineage(origin.scope(), rawLineage, enrichedLineage, raw, enriched);
        String institution = origin.scope().institution();
        if (raw.merchantInfo() == null || enriched.merchantInfo() == null
                || raw.merchantInfo().merchantId() == null || raw.merchantInfo().merchantId().isBlank()
                || !Objects.equals(raw.merchantInfo().merchantId(), enriched.merchantInfo().merchantId())
                || raw.transactionId() == null || raw.transactionId().isBlank()) { throw denied(); }

        String merchant = pseudonyms.merchant(institution, raw.merchantInfo().merchantId());
        String transaction = pseudonyms.transaction(institution, origin.scope().source(), raw.transactionId());
        String sourceEvent = pseudonyms.sourceEvent(institution, origin.scope().source(), raw.eventId());
        String occurrence = pseudonyms.occurrence(institution, origin.scope().source(), raw.eventId(), rawLineage.receiptId());
        var expected = new MerchantAssertionVerifier.ExpectedSourceBinding(origin, rawLineage.receiptId(),
                rawLineage.eventCommitment(), enrichedLineage.eventCommitment(), transaction, occurrence,
                merchant, pseudonyms.mappingVersion(), pseudonyms.mappingCommitment());
        MerchantAssertionVerifier.Result result = verifier.verifyObservation(observationProof, originalObservationAssertion, expected);
        if (!(result instanceof MerchantAssertionVerifier.VerifiedObservation observationEvidence)) { throw denied(); }
        MerchantObservationAssertion claim = observationEvidence.assertion();
        MerchantObservationStore.Scope scope = pseudonyms.scope(origin.scope());
        if (claim.identityVersion() != scope.identityVersion() || !claim.sourceEventId().equals(raw.eventId())
                || !claim.eventTime().equals(raw.createdAt()) || !claim.eventTime().equals(enriched.createdAt())) { throw denied(); }
        MerchantAssertionAnchor anchor = MerchantAssertionAnchor.observation(observationEvidence);
        Observation input = new Observation(scope, sourceEvent, transaction, occurrence, merchant, claim.sequence(),
                claim.eventTime(), claim.sourceObservedAt(), enrichedLineage.eventCommitment(), anchor.commitment());
        return new VerifiedInput(origin, raw, enriched, observationEvidence, input);
    }

    void seal(Ticket ticket, byte[] coverageProof, byte[] originalCoverageAssertion) {
        requireCurrent(ticket);
        MongoMerchantObservationStore.StoredCoverage stored = store.storedCoverage(ticket.input.scope());
        MerchantAssertionVerifier.VerifiedCoverage previous = null;
        long previousSequence = 0;
        if (stored != null) {
            if (stored.seal() == null) {
                if (stored.anchor() != null) { throw denied(); }
            } else {
                if (stored.anchor() == null) { throw denied(); }
                previousSequence = stored.seal().throughSequence();
                if (previousSequence == Long.MAX_VALUE) { throw denied(); }
                previous = verifier.verifyStoredCoverage(stored.anchor(), ticket.origin, stored.seal());
                if (previous == null || verifier.currentDenial(previous) != null) { throw denied(); }
                if (Arrays.equals(coverageProof, previous.proofBytes())
                        && Arrays.equals(originalCoverageAssertion, previous.originalAssertionBytes())) {
                    AuthorityContext replayContext = new AuthorityContext(ticket.input, ticket.origin, ticket.raw,
                            ticket.enriched, ticket.observation, stored.seal(), previous);
                    withContext(replayContext, () -> { store.seal(stored.seal()); return null; });
                    requireCurrent(ticket);
                    return;
                }
            }
        }
        MerchantAssertionVerifier.Result result = verifier.verifyCoverage(coverageProof, originalCoverageAssertion,
                ticket.origin, previous);
        if (!(result instanceof MerchantAssertionVerifier.VerifiedCoverage coverageEvidence)) { throw denied(); }
        MerchantCoverageAssertion claim = coverageEvidence.assertion();
        if (claim.fromSequence() != previousSequence + 1 || claim.throughSequence() < ticket.input.sequence()) { throw denied(); }
        MerchantAssertionAnchor anchor = MerchantAssertionAnchor.coverage(coverageEvidence);
        CoverageSeal seal = new CoverageSeal(ticket.input.scope(), claim.throughSequence(), claim.coverageStart(),
                claim.watermark(), anchor.commitment());
        AuthorityContext context = new AuthorityContext(ticket.input, ticket.origin, ticket.raw, ticket.enriched,
                ticket.observation, seal, coverageEvidence);
        withContext(context, () -> { store.seal(seal); return null; });
        requireCurrent(ticket);
    }

    CommitResult commit(Ticket ticket) {
        requireCurrent(ticket);
        AuthorityContext context = new AuthorityContext(ticket.input, ticket.origin, ticket.raw, ticket.enriched,
                ticket.observation, null, null);
        CommitResult result = withContext(context, () -> store.commit(ticket.input));
        requireCurrent(ticket);
        return result;
    }

    /** Stage B appends only the source's persisted INITIAL SCORED occurrence to the completed Stage A window. */
    void bindScored(Ticket ticket, SourceOccurrenceAccess occurrences, SourceOccurrenceAccess.Reference reference) {
        requireCurrent(ticket);
        if (occurrences == null || reference == null) { throw denied(); }
        SourceLineage authorization = source.enriched(ticket.enriched);
        SourceScoredOccurrence scored;
        try { scored = occurrences.find(authorization, reference).orElse(null); }
        catch (RuntimeException unavailable) { throw denied(); }
        if (!matchesScored(ticket, reference, scored)) { throw denied(); }
        AuthorityContext context = new AuthorityContext(ticket.input, ticket.origin, ticket.raw, ticket.enriched,
                ticket.observation, null, null);
        withContext(context, () -> { store.bindScored(ticket.input, scored); return null; });
        requireCurrent(ticket);
        SourceScoredOccurrence current;
        try { current = occurrences.find(source.enriched(ticket.enriched), reference).orElse(null); }
        catch (RuntimeException unavailable) { throw denied(); }
        if (!matchesScored(ticket, reference, current)) { throw denied(); }
    }

    SourceScoredOccurrence resolveScored(Ticket ticket, SourceOccurrenceAccess occurrences,
                                         SourceOccurrenceAccess.Reference reference) {
        requireCurrent(ticket);
        if (occurrences == null || reference == null) { throw denied(); }
        SourceLineage authorization = source.enriched(ticket.enriched);
        if (authorization.state() != SourceLineage.State.VERIFIED) { throw denied(); }
        SourceScoredOccurrence scored;
        try { scored = occurrences.find(authorization, reference).orElse(null); }
        catch (RuntimeException unavailable) { throw denied(); }
        if (scored != null && !matchesScored(ticket, reference, scored)) { throw denied(); }
        return scored;
    }

    DiagnosticSnapshot readDiagnostic(Ticket ticket, SourceScoredOccurrence scored) {
        requireCurrent(ticket);
        AuthorityContext context = new AuthorityContext(ticket.input, ticket.origin, ticket.raw, ticket.enriched,
                ticket.observation, null, null);
        DiagnosticSnapshot result = withContext(context, () -> store.readDiagnostic(ticket.input, scored));
        requireCurrent(ticket);
        return result;
    }

    boolean matchesReadGrant(Ticket ticket, MerchantObservationStore.Scope scope, String merchant) {
        return ticket != null && ticket.owner == this && ticket.input.scope().equals(scope)
                && ticket.input.merchant().equals(merchant);
    }

    void recheck(Ticket ticket) { requireCurrent(ticket); }

    private static boolean matchesScored(Ticket ticket, SourceOccurrenceAccess.Reference reference,
                                         SourceScoredOccurrence scored) {
        return ticket != null && reference != null && scored != null
                && com.frauddetection.common.events.source.MongoSourceOccurrenceStore.INITIAL_RUN.equals(scored.runId())
                && reference.eventId().equals(scored.scoredEventId())
                && reference.createdAt().equals(scored.scoredCreatedAt())
                && reference.fingerprint().equals(scored.scoredFingerprint())
                && ticket.origin.scope().equals(scored.source())
                && ticket.observation.assertion().receiptId().equals(scored.receiptId())
                && ticket.observation.assertion().enrichedCommitment().equals(scored.enrichedCommitment());
    }

    private SourceSignatureVerifier.Verified verifiedOrigin(byte[] proof, byte[] payload) {
        for (String audience : originAudiences) {
            SourceSignatureVerifier.Result result = originVerifier.verify(proof, payload,
                    new SourceSignatureVerifier.Context("TRANSACTION_SOURCE_ATTESTATION", audience));
            if (result instanceof SourceSignatureVerifier.Verified verified) { return verified; }
        }
        throw denied();
    }

    private void requireCurrent(Ticket ticket) {
        if (ticket == null || ticket.owner != this || verifier.currentDenial(ticket.observation) != null) { throw denied(); }
        SourceLineage rawLineage = source.raw(ticket.raw);
        SourceLineage enrichedLineage = source.enriched(ticket.enriched);
        requireLineage(ticket.origin.scope(), rawLineage, enrichedLineage, ticket.raw, ticket.enriched);
        MerchantObservationAssertion claim = ticket.observation.assertion();
        if (!claim.receiptId().equals(rawLineage.receiptId())
                || !claim.rawCommitment().equals(rawLineage.eventCommitment())
                || !claim.enrichedCommitment().equals(enrichedLineage.eventCommitment())) { throw denied(); }
    }

    private void requireLineage(SourceScope scope, SourceLineage rawLineage, SourceLineage enrichedLineage,
                                TransactionRawEvent raw, TransactionEnrichedEvent enriched) {
        if (raw == null || enriched == null || rawLineage.state() != SourceLineage.State.VERIFIED
                || enrichedLineage.state() != SourceLineage.State.VERIFIED
                || !scope.equals(rawLineage.source()) || !scope.equals(enrichedLineage.source())
                || !rawLineage.receiptId().equals(enrichedLineage.receiptId())
                || !SourceReceiptIdentity.identity(scope).equals(rawLineage.receiptId())
                || !scope.sourceEventId().equals(raw.eventId()) || !scope.eventTime().equals(raw.createdAt())
                || !raw.createdAt().equals(enriched.createdAt())
                || !com.frauddetection.common.events.source.SourceEventCommitment.raw(raw).equals(rawLineage.eventCommitment())
                || !com.frauddetection.common.events.source.SourceEventCommitment.enriched(enriched).equals(enrichedLineage.eventCommitment())) {
            throw denied();
        }
    }

    private <T> T withContext(AuthorityContext context, Supplier<T> operation) {
        if (active.get() != null) { throw denied(); }
        active.set(context);
        try { return operation.get(); }
        finally { active.remove(); }
    }

    private static SecurityException denied() { return new SecurityException("MERCHANT_VERIFIED_ADMISSION_DENIED"); }

    static List<SourceLineage> resolveSourceBindings(MongoSourceLineage source, List<EnrichedBinding> bindings) {
        if (source == null) { throw denied(); }
        return resolveSourceBindings(source::enrichedBindings, bindings);
    }

    static List<SourceLineage> resolveSourceBindings(Function<List<EnrichedBinding>, List<SourceLineage>> lookup,
                                                     List<EnrichedBinding> bindings) {
        if (lookup == null || bindings == null) { throw denied(); }
        if (bindings.isEmpty()) { return List.of(); }
        List<SourceLineage> resolved = new ArrayList<>(bindings.size());
        for (int start = 0; start < bindings.size(); start += MongoSourceLineage.MAX_ENRICHED_BINDING_BATCH) {
            List<EnrichedBinding> batch = bindings.subList(start,
                    Math.min(start + MongoSourceLineage.MAX_ENRICHED_BINDING_BATCH, bindings.size()));
            List<SourceLineage> lineages = lookup.apply(batch);
            if (lineages == null || lineages.size() != batch.size()) { throw denied(); }
            resolved.addAll(lineages);
        }
        return List.copyOf(resolved);
    }

    private final class Admission implements MerchantObservationStore.AdmissionVerifier {
        @Override public void requireObservation(Observation observation) {
            AuthorityContext context = active.get();
            if (context == null || context.observation == null || !context.input.equals(observation)
                    || verifier.currentDenial(context.observation) != null) { throw denied(); }
        }

        @Override public void requireCoverage(CoverageSeal seal) {
            AuthorityContext context = active.get();
            if (context == null || context.coverage == null || context.coverageSeal == null
                    || !context.coverageSeal.equals(seal) || verifier.currentDenial(context.coverage) != null) { throw denied(); }
        }

        @Override public void requireRead(MerchantObservationStore.Scope scope) {
            AuthorityContext context = active.get();
            if (context == null || context.observation == null || !context.input.scope().equals(scope)
                    || verifier.currentDenial(context.observation) != null) { throw denied(); }
        }

        @Override public MerchantAssertionAnchor observationAnchor(Observation observation) {
            requireObservation(observation);
            return MerchantAssertionAnchor.observation(active.get().observation);
        }

        @Override public MerchantAssertionAnchor coverageAnchor(CoverageSeal seal) {
            requireCoverage(seal);
            return MerchantAssertionAnchor.coverage(active.get().coverage);
        }

        @Override public void requirePersistedObservation(Observation observation, MerchantAssertionAnchor anchor) {
            if (anchor == null || anchor.role() != MerchantAssertionVerifier.Role.OBSERVATION) { throw denied(); }
            MerchantObservationAssertion claim;
            try { claim = MerchantAssertionCodec.parseObservation(anchor.assertionBytes()); }
            catch (RuntimeException malformed) { throw denied(); }
            SourceLineage lineage = source.enrichedBinding(claim.receiptId(), claim.rawCommitment(), claim.enrichedCommitment());
            if (!verifier.verifyStoredObservation(anchor, lineage, observation, pseudonyms)) { throw denied(); }
        }

        @Override public void requireCoverageChain(Scope scope, CoverageGeneration coverageFence,
                                                   List<CoverageGeneration> activeGenerations,
                                                   CoverageGeneration latestGeneration) {
            AuthorityContext context = active.get();
            if (context == null || context.observation == null || !context.input.scope().equals(scope)
                    || verifier.currentDenial(context.observation) != null) { throw denied(); }
            verifyCoverageChain(context, scope, coverageFence, activeGenerations, latestGeneration,
                    latestGeneration != null);
        }

        @Override public void requireHistoryRead(MerchantObservationStore.HistoryRead read) {
            AuthorityContext context = active.get();
            if (context == null || context.observation == null
                    || !context.input.equals(read.current()) || verifier.currentDenial(context.observation) != null) { throw denied(); }
            requireCurrent(new Ticket(VerifiedMerchantAdmission.this, context.origin, context.raw, context.enriched,
                    context.observation, context.input, MerchantObservationStore.StageResult.EXISTING));
            CoverageGeneration fence = read.coverageFence();
            CoverageGeneration latest = null;
            try { latest = MerchantObservationDocumentCodec.coverageGenerationFromAnchor(
                    read.current().scope(), read.coverageAnchor()); }
            catch (RuntimeException invalid) { throw denied(); }
            MerchantAssertionVerifier.VerifiedCoverage verifiedLatest = verifyCoverageChain(context, read.current().scope(),
                    fence, read.coverageGenerations(), latest, true);
            if (read.coverage() == null || read.coverageAnchor() == null
                    || verifier.currentDenial(verifiedLatest) != null
                    || !verifiedLatest.assertionCommitment().equals(read.coverageAnchor().commitment())
                    || !read.coverage().evidenceCommitment().equals(read.coverageAnchor().commitment())
                    || read.coverage().throughSequence() != verifiedLatest.assertion().throughSequence()
                    || !read.coverage().coverageStart().equals(verifiedLatest.assertion().coverageStart())
                    || !read.coverage().watermark().equals(verifiedLatest.assertion().watermark())
                    || !covers(read.current().sequence(), fence, read.coverageGenerations())
                    || read.coverage().watermark().isBefore(read.current().eventTime())) { throw denied(); }
            if (read.observations().size() != read.observationAnchors().size()) { throw denied(); }
            for (int start = 0; start < read.observations().size(); start += MongoSourceLineage.MAX_ENRICHED_BINDING_BATCH) {
                int end = Math.min(start + MongoSourceLineage.MAX_ENRICHED_BINDING_BATCH, read.observations().size());
                List<MerchantObservationStore.Observation> observations = read.observations().subList(start, end);
                List<MerchantAssertionAnchor> anchors = read.observationAnchors().subList(start, end);
                List<EnrichedBinding> bindings = anchors.stream().map(this::sourceBinding).toList();
                List<SourceLineage> lineages = resolveSourceBindings(source, bindings);
                if (lineages.size() != observations.size()) { throw denied(); }
                for (int index = 0; index < observations.size(); index++) {
                    MerchantObservationStore.Observation observation = observations.get(index);
                    SourceLineage lineage = lineages.get(index);
                    if (!covers(observation.sequence(), fence, read.coverageGenerations())
                            || lineage == null || lineage.state() != SourceLineage.State.VERIFIED
                            || !verifier.verifyStoredObservation(anchors.get(index), lineage, observation, pseudonyms)) {
                        throw denied();
                    }
                }
            }
        }

        private EnrichedBinding sourceBinding(MerchantAssertionAnchor anchor) {
            if (anchor == null || anchor.role() != MerchantAssertionVerifier.Role.OBSERVATION) { throw denied(); }
            try {
                MerchantObservationAssertion claim = MerchantAssertionCodec.parseObservation(anchor.assertionBytes());
                return new EnrichedBinding(claim.receiptId(), claim.rawCommitment(), claim.enrichedCommitment());
            } catch (RuntimeException malformed) { throw denied(); }
        }


        private CoverageSeal coverageSeal(CoverageGeneration generation) {
            return new CoverageSeal(generation.scope(), generation.throughSequence(), generation.coverageStart(),
                    generation.watermark(), generation.commitment());
        }

        private MerchantAssertionVerifier.VerifiedCoverage verifyCoverageChain(AuthorityContext context, Scope scope,
                                                                                CoverageGeneration fence,
                                                                                List<CoverageGeneration> activeGenerations,
                                                                                CoverageGeneration latest,
                                                                                boolean requireLatest) {
            if (activeGenerations == null || activeGenerations.size() > MerchantObservationStore.MAX_ACTIVE_COVERAGE_GENERATIONS
                    || !pseudonyms.scope(context.origin.scope()).equals(scope)
                    || fence != null && !fence.scope().equals(scope)
                    || latest != null && !latest.scope().equals(scope)) { throw denied(); }
            MerchantAssertionVerifier.VerifiedCoverage previous = null;
            long expectedGeneration = 1;
            long expectedFromSequence = 1;
            String previousCommitment = null;
            if (fence != null) {
                // Prefix compaction advances only after verifying the complete chain; the retained fence is that chain's checkpoint.
                previous = verifier.verifyStoredCoverage(fence.anchor(), context.origin, coverageSeal(fence));
                if (previous == null || verifier.currentDenial(previous) != null || !sameCoverage(previous.assertion(), fence)) {
                    throw denied();
                }
                if (fence.generation() == Long.MAX_VALUE || fence.throughSequence() == Long.MAX_VALUE) { throw denied(); }
                expectedGeneration = fence.generation() + 1;
                expectedFromSequence = fence.throughSequence() + 1;
                previousCommitment = fence.commitment();
            }
            for (CoverageGeneration generation : activeGenerations) {
                if (!generation.scope().equals(scope) || generation.generation() != expectedGeneration
                        || generation.fromSequence() != expectedFromSequence
                        || !Objects.equals(generation.previousCommitment(), previousCommitment)) { throw denied(); }
                MerchantAssertionVerifier.VerifiedCoverage verified = verifier.verifyStoredCoverage(
                        generation.anchor(), context.origin, coverageSeal(generation), previous);
                if (verified == null || verifier.currentDenial(verified) != null
                        || !sameCoverage(verified.assertion(), generation)) { throw denied(); }
                previous = verified;
                previousCommitment = generation.commitment();
                if (generation.generation() == Long.MAX_VALUE || generation.throughSequence() == Long.MAX_VALUE) { throw denied(); }
                expectedGeneration = generation.generation() + 1;
                expectedFromSequence = generation.throughSequence() + 1;
            }
            if (latest == null) {
                if (requireLatest || previous != null || fence != null || !activeGenerations.isEmpty()) { throw denied(); }
                return null;
            }
            if (previous == null || !previous.assertionCommitment().equals(latest.commitment())
                    || !MerchantAssertionAnchor.coverage(previous).equals(latest.anchor())
                    || !sameCoverage(previous.assertion(), latest)) { throw denied(); }
            return previous;
        }

        private boolean sameCoverage(MerchantCoverageAssertion claim, CoverageGeneration generation) {
            return claim.generation() == generation.generation() && claim.fromSequence() == generation.fromSequence()
                    && claim.throughSequence() == generation.throughSequence()
                    && claim.coverageStart().equals(generation.coverageStart())
                    && claim.watermark().equals(generation.watermark())
                    && Objects.equals(claim.previousCoverageCommitment(), generation.previousCommitment());
        }

        private boolean covers(long sequence, CoverageGeneration fence, List<CoverageGeneration> generations) {
            return fence != null && fence.covers(sequence) || generations.stream().anyMatch(generation -> generation.covers(sequence));
        }
    }

    @JsonIgnoreType
    static final class Ticket {
        private final VerifiedMerchantAdmission owner;
        private final SourceSignatureVerifier.Verified origin;
        private final TransactionRawEvent raw;
        private final TransactionEnrichedEvent enriched;
        private final MerchantAssertionVerifier.VerifiedObservation observation;
        private final Observation input;
        private final MerchantObservationStore.StageResult status;

        private Ticket(VerifiedMerchantAdmission owner, SourceSignatureVerifier.Verified origin,
                       TransactionRawEvent raw, TransactionEnrichedEvent enriched,
                       MerchantAssertionVerifier.VerifiedObservation observation, Observation input,
                       MerchantObservationStore.StageResult status) {
            this.owner = owner; this.origin = origin; this.raw = raw; this.enriched = enriched;
            this.observation = observation; this.input = input; this.status = status;
        }
        MerchantObservationStore.StageResult status() { return status; }
        @Override public String toString() { return "MerchantAdmissionTicket[redacted]"; }
    }

    private record AuthorityContext(Observation input, SourceSignatureVerifier.Verified origin,
                                    TransactionRawEvent raw, TransactionEnrichedEvent enriched,
                                    MerchantAssertionVerifier.VerifiedObservation observation,
                                    CoverageSeal coverageSeal, MerchantAssertionVerifier.VerifiedCoverage coverage) { }

    private record VerifiedInput(SourceSignatureVerifier.Verified origin, TransactionRawEvent raw,
                                 TransactionEnrichedEvent enriched,
                                 MerchantAssertionVerifier.VerifiedObservation observation, Observation input) { }
}
