package com.frauddetection.enricher.device;

import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceOccurrenceAccess;
import com.frauddetection.common.events.source.SourceScoredOccurrence;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.enricher.graph.GraphEvidenceRef;
import com.frauddetection.enricher.graph.GraphIdentityScope;
import com.frauddetection.enricher.graph.GraphOccurrenceRef;
import com.frauddetection.enricher.graph.GraphScoredOccurrenceRef;
import com.mongodb.client.MongoClient;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Device-owned, read-only bridge. It is inert unless assembled with the explicit test-only grants;
 * no Spring configuration or production grant issuer registers this adapter.
 */
final class DeviceGraphRelationAuthority implements DeviceGraphRelationPort {
    private static final long MAX_VALIDATION_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final MongoDeviceObservationStore store;
    private final MongoSourceLineage source;
    private final SourceOccurrenceAccess scoredOccurrences;
    private final DevicePseudonyms pseudonyms;
    private final Clock clock;

    static DeviceGraphRelationAuthority testOnly(MongoClient client, String database, String mongoReaderPrincipal,
                                                  DeviceEpoch epoch, MongoSourceLineage source,
                                                  SourceVerificationProperties origins,
                                                  List<DeviceAssertionVerifier.Grant> grants,
                                                  SourceOccurrenceAccess scoredOccurrences,
                                                  DevicePseudonyms pseudonyms, Clock clock) {
        DeviceAssertionVerifier verifier = new DeviceAssertionVerifier(origins, grants, clock);
        DeviceObservationStore.HistoryAuthority currentAuthority = VerifiedDeviceAdmission.historyAuthority(
                source, epoch, verifier, pseudonyms);
        MongoDeviceObservationStore ownerStore = MongoDeviceObservationStore.readOnly(client, database, clock,
                epoch, currentAuthority, pseudonyms, mongoReaderPrincipal);
        return new DeviceGraphRelationAuthority(ownerStore, source, scoredOccurrences, pseudonyms, clock);
    }

    private DeviceGraphRelationAuthority(MongoDeviceObservationStore store, MongoSourceLineage source,
                                         SourceOccurrenceAccess scoredOccurrences, DevicePseudonyms pseudonyms,
                                         Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.source = Objects.requireNonNull(source);
        this.scoredOccurrences = Objects.requireNonNull(scoredOccurrences);
        this.pseudonyms = Objects.requireNonNull(pseudonyms);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<VerifiedDeviceGraphRelation> admit(String ownerReference,
                                                       DeviceGraphRelationGrant relationGrant) {
        if (ownerReference == null || !DeviceObservationStore.TOKEN.matcher(ownerReference).matches()
                || relationGrant == null) {
            return Optional.empty();
        }
        return resolveCurrent(ownerReference, null, relationGrant, null, null);
    }

    @Override
    public Optional<VerifiedDeviceGraphRelation> resolve(GraphEvidenceRef reference,
                                                         DeviceGraphRelationGrant relationGrant,
                                                         DeviceGraphReadGrant readGrant,
                                                         String requestingPrincipal) {
        if (reference == null || relationGrant == null || readGrant == null || requestingPrincipal == null) {
            return Optional.empty();
        }
        return resolveCurrent(reference.ownerAssertionReference(), reference, relationGrant, readGrant, requestingPrincipal);
    }

    private Optional<VerifiedDeviceGraphRelation> resolveCurrent(String ownerReference, GraphEvidenceRef expected,
                                                                  DeviceGraphRelationGrant relationGrant,
                                                                  DeviceGraphReadGrant readGrant,
                                                                  String requestingPrincipal) {
        long started = System.nanoTime();
        try {
            GraphOccurrenceSnapshot before = store.graphOccurrence(ownerReference, null);
            if (before == null || expired(started)) { return Optional.empty(); }

            SourceLineage initialSource = resolveSource(before);
            if (!verifiedSource(initialSource, before, relationGrant, readGrant, requestingPrincipal) || expired(started)) {
                return Optional.empty();
            }
            SourceScoredOccurrence initialScore = scoredOccurrences.find(initialSource, before.scoredReference()).orElse(null);
            if (!exactScore(initialScore, initialSource, before) || expired(started)) { return Optional.empty(); }

            GraphOccurrenceSnapshot current = store.graphOccurrence(ownerReference, initialScore);
            if (current == null || !before.equals(current) || expired(started)) { return Optional.empty(); }
            SourceLineage finalSource = resolveSource(current);
            if (!verifiedSource(finalSource, current, relationGrant, readGrant, requestingPrincipal)) {
                return Optional.empty();
            }
            SourceScoredOccurrence finalScore = scoredOccurrences.find(finalSource, current.scoredReference()).orElse(null);
            if (!exactScore(finalScore, finalSource, current) || !sameScore(initialScore, finalScore)
                    || expired(started)) { return Optional.empty(); }

            GraphEvidenceRef resolved = evidence(ownerReference, current, finalSource.source(), finalScore, relationGrant);
            if (expected != null && !expected.equals(resolved)) { return Optional.empty(); }
            Instant validUntil = current.expiresAt();
            if (relationGrant.expiresAt().isBefore(validUntil)) { validUntil = relationGrant.expiresAt(); }
            if (readGrant != null && readGrant.expiresAt().isBefore(validUntil)) { validUntil = readGrant.expiresAt(); }
            if (!validUntil.isAfter(clock.instant())) { return Optional.empty(); }
            return Optional.of(new VerifiedDeviceGraphRelation(resolved, validUntil));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private SourceLineage resolveSource(GraphOccurrenceSnapshot snapshot) {
        DeviceObservationStore.Provenance provenance = snapshot.provenance();
        return source.enrichedBinding(provenance.receiptId(), provenance.rawCommitment(), provenance.enrichedCommitment());
    }

    private boolean verifiedSource(SourceLineage lineage, GraphOccurrenceSnapshot snapshot,
                                   DeviceGraphRelationGrant relationGrant, DeviceGraphReadGrant readGrant,
                                   String requestingPrincipal) {
        if (lineage == null || lineage.state() != SourceLineage.State.VERIFIED || lineage.source() == null
                || !lineage.receiptId().equals(snapshot.provenance().receiptId())
                || !lineage.eventCommitment().equals(snapshot.provenance().enrichedCommitment())) { return false; }
        SourceScope scope = lineage.source();
        return epochMatches(scope)
                && snapshot.institution().equals(pseudonyms.institution(scope.institution()))
                && snapshot.source().equals(DevicePseudonyms.sourceCode(scope.source()))
                && relationGrant.currentFor(scope, snapshot.pseudonymKeyVersion(), clock.instant())
                && (readGrant == null || readGrant.currentFor(scope, snapshot.pseudonymKeyVersion(),
                clock.instant(), requestingPrincipal));
    }

    private boolean epochMatches(SourceScope scope) {
        return DeviceAssertionVerifier.TRANSACTION_PURPOSE.equals(scope.purpose())
                && store.epoch().sourceIssuer().equals(scope.issuer())
                && store.epoch().sourceEpoch().equals(scope.sourceEpoch());
    }

    private boolean exactScore(SourceScoredOccurrence score, SourceLineage authorization,
                               GraphOccurrenceSnapshot snapshot) {
        SourceOccurrenceAccess.Reference reference = snapshot.scoredReference();
        return score != null && MongoSourceOccurrenceStore.INITIAL_RUN.equals(score.runId())
                && score.source().equals(authorization.source())
                && score.receiptId().equals(authorization.receiptId())
                && score.enrichedCommitment().equals(authorization.eventCommitment())
                && score.scoredEventId().equals(reference.eventId())
                && score.scoredCreatedAt().equals(reference.createdAt())
                && score.scoredFingerprint().equals(reference.fingerprint());
    }

    private static boolean sameScore(SourceScoredOccurrence left, SourceScoredOccurrence right) {
        return left != null && right != null && left.source().equals(right.source())
                && left.receiptId().equals(right.receiptId())
                && left.enrichedCommitment().equals(right.enrichedCommitment())
                && left.scoredEventId().equals(right.scoredEventId())
                && left.scoredCreatedAt().equals(right.scoredCreatedAt())
                && left.scoredFingerprint().equals(right.scoredFingerprint())
                && left.runId().equals(right.runId());
    }

    private GraphEvidenceRef evidence(String ownerReference, GraphOccurrenceSnapshot snapshot,
                                     SourceScope sourceScope, SourceScoredOccurrence score,
                                     DeviceGraphRelationGrant relationGrant) {
        GraphIdentityScope graphScope = new GraphIdentityScope(
                pseudonyms.graphReference("scope-issuer", sourceScope.issuer()),
                pseudonyms.graphReference("scope-institution", sourceScope.institution()),
                pseudonyms.graphReference("scope-source", sourceScope.source()),
                pseudonyms.graphReference("scope-epoch", sourceScope.sourceEpoch()),
                DeviceEpoch.VERSION);
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(graphScope, ownerReference, snapshot.eventTime());
        DeviceObservationStore.Provenance provenance = snapshot.provenance();
        DeviceObservationStore.CoverageAuthority coverage = snapshot.occurrenceCoverageAuthority();
        String coverageReference = pseudonyms.graphReference("coverage", coverage.coverageId(),
                Long.toString(snapshot.coverageGeneration()), snapshot.coverageCommitment());
        String policyReference = pseudonyms.graphReference("policy", provenance.deviceIssuer(),
                provenance.deviceKeyVersion(), provenance.devicePolicyVersion(), provenance.deviceKeyCommitment(),
                provenance.authorizationCommitment(), relationGrant.grantId());
        GraphScoredOccurrenceRef scored = new GraphScoredOccurrenceRef(
                pseudonyms.graphReference("score-event", score.scoredEventId()), score.scoredCreatedAt(),
                score.scoredFingerprint(), score.runId());
        return new GraphEvidenceRef(graphScope, occurrence, snapshot.deviceToken(), snapshot.pseudonymKeyVersion(),
                provenance.receiptId(), provenance.rawCommitment(), provenance.enrichedCommitment(), ownerReference,
                coverageReference, policyReference, scored, snapshot.stageASnapshotCommitment(), snapshot.coverageStart(),
                snapshot.eventTime(), snapshot.coverageGeneration(), snapshot.coverageWatermark(), snapshot.expiresAt());
    }

    private boolean expired(long started) {
        return System.nanoTime() - started > MAX_VALIDATION_NANOS;
    }
}
