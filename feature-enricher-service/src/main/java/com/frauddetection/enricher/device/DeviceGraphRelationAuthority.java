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
import com.frauddetection.enricher.graph.GraphReadReason;
import com.frauddetection.enricher.graph.GraphScoredOccurrenceRef;
import com.mongodb.client.MongoClient;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
    public DeviceGraphRelationResolution admit(String ownerReference,
                                               DeviceGraphRelationGrant relationGrant) {
        if (ownerReference == null || !DeviceObservationStore.TOKEN.matcher(ownerReference).matches()
                || relationGrant == null) {
            return DeviceGraphRelationResolution.denied(GraphReadReason.REFERENCE_INVALID);
        }
        return resolveCurrent(ownerReference, null, relationGrant, null, null);
    }

    @Override
    public DeviceGraphRelationResolution resolve(GraphEvidenceRef reference,
                                                 DeviceGraphRelationGrant relationGrant,
                                                 DeviceGraphReadGrant readGrant,
                                                 String requestingPrincipal) {
        if (reference == null || relationGrant == null || readGrant == null || requestingPrincipal == null) {
            return DeviceGraphRelationResolution.denied(GraphReadReason.REFERENCE_INVALID);
        }
        return resolveCurrent(reference.ownerAssertionReference(), reference, relationGrant, readGrant, requestingPrincipal);
    }

    private DeviceGraphRelationResolution resolveCurrent(String ownerReference, GraphEvidenceRef expected,
                                                          DeviceGraphRelationGrant relationGrant,
                                                          DeviceGraphReadGrant readGrant,
                                                          String requestingPrincipal) {
        long started = System.nanoTime();
        try {
            MongoDeviceObservationStore.GraphOccurrenceRead beforeRead = store.graphOccurrence(ownerReference, null);
            if (beforeRead.state() != MongoDeviceObservationStore.GraphOccurrenceRead.State.VERIFIED) {
                return ownerOutcome(beforeRead);
            }
            GraphOccurrenceSnapshot before = beforeRead.snapshot();
            if (expired(started)) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }

            SourceLineage initialSource = resolveSource(before);
            DeviceGraphRelationResolution initialAuthorization = sourceOutcome(initialSource, before, relationGrant,
                    readGrant, requestingPrincipal);
            if (initialAuthorization != null) { return initialAuthorization; }
            if (expired(started)) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }
            SourceOccurrenceAccess.Resolution initialScoreResult = scoredOccurrences.resolve(initialSource, before.scoredReference());
            DeviceGraphRelationResolution initialScoreOutcome = scoreOutcome(initialScoreResult);
            if (initialScoreOutcome != null) { return initialScoreOutcome; }
            SourceScoredOccurrence initialScore = initialScoreResult.occurrence();
            if (!exactScore(initialScore, initialSource, before)) { return DeviceGraphRelationResolution.conflict(); }
            if (expired(started)) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }

            MongoDeviceObservationStore.GraphOccurrenceRead currentRead = store.graphOccurrence(ownerReference, initialScore);
            if (currentRead.state() != MongoDeviceObservationStore.GraphOccurrenceRead.State.VERIFIED) {
                return ownerOutcome(currentRead);
            }
            GraphOccurrenceSnapshot current = currentRead.snapshot();
            if (!before.equals(current)) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
            if (expired(started)) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }
            SourceLineage finalSource = resolveSource(current);
            DeviceGraphRelationResolution finalAuthorization = sourceOutcome(finalSource, current, relationGrant,
                    readGrant, requestingPrincipal);
            if (finalAuthorization != null) { return finalAuthorization; }
            SourceOccurrenceAccess.Resolution finalScoreResult = scoredOccurrences.resolve(finalSource, current.scoredReference());
            DeviceGraphRelationResolution finalScoreOutcome = scoreOutcome(finalScoreResult);
            if (finalScoreOutcome != null) { return finalScoreOutcome; }
            SourceScoredOccurrence finalScore = finalScoreResult.occurrence();
            if (!exactScore(finalScore, finalSource, current) || !sameScore(initialScore, finalScore)) {
                return DeviceGraphRelationResolution.conflict();
            }
            if (expired(started)) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }

            GraphEvidenceRef resolved = evidence(ownerReference, current, finalSource.source(), finalScore, relationGrant);
            if (expected != null && !expected.equals(resolved)) {
                return DeviceGraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
            }
            Instant validUntil = current.expiresAt();
            if (relationGrant.expiresAt().isBefore(validUntil)) { validUntil = relationGrant.expiresAt(); }
            if (readGrant != null && readGrant.expiresAt().isBefore(validUntil)) { validUntil = readGrant.expiresAt(); }
            if (!validUntil.isAfter(clock.instant())) {
                return DeviceGraphRelationResolution.unavailable(GraphReadReason.RETENTION_EXPIRED);
            }
            return DeviceGraphRelationResolution.verified(new VerifiedDeviceGraphRelation(resolved, validUntil));
        } catch (RuntimeException unavailable) {
            return DeviceGraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
        }
    }

    private SourceLineage resolveSource(GraphOccurrenceSnapshot snapshot) {
        DeviceObservationStore.Provenance provenance = snapshot.provenance();
        return source.enrichedBinding(provenance.receiptId(), provenance.rawCommitment(), provenance.enrichedCommitment());
    }

    private DeviceGraphRelationResolution sourceOutcome(SourceLineage lineage, GraphOccurrenceSnapshot snapshot,
                                                        DeviceGraphRelationGrant relationGrant,
                                                        DeviceGraphReadGrant readGrant,
                                                        String requestingPrincipal) {
        if (lineage == null) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.SOURCE_UNAVAILABLE); }
        if (lineage.state() != SourceLineage.State.VERIFIED) {
            return switch (lineage.state()) {
                case ORDER_PENDING -> DeviceGraphRelationResolution.pending(GraphReadReason.SOURCE_UNAVAILABLE);
                case UNAVAILABLE -> DeviceGraphRelationResolution.unavailable(GraphReadReason.SOURCE_UNAVAILABLE);
                case REVOKED -> DeviceGraphRelationResolution.denied(GraphReadReason.AUTHORITY_REVOKED);
                case CONFLICT -> DeviceGraphRelationResolution.conflict();
                case UNVERIFIED -> DeviceGraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                case VERIFIED -> throw new IllegalArgumentException("DEVICE_GRAPH_SOURCE_STATE_INVALID");
            };
        }
        if (lineage.source() == null || !lineage.receiptId().equals(snapshot.provenance().receiptId())
                || !lineage.eventCommitment().equals(snapshot.provenance().enrichedCommitment())) {
            return DeviceGraphRelationResolution.conflict();
        }
        SourceScope scope = lineage.source();
        if (!epochMatches(scope) || !snapshot.institution().equals(pseudonyms.institution(scope.institution()))
                || !snapshot.source().equals(DevicePseudonyms.sourceCode(scope.source()))) {
            return DeviceGraphRelationResolution.denied(GraphReadReason.SCOPE_MISMATCH);
        }
        Instant now = clock.instant();
        if (!relationGrant.currentFor(scope, snapshot.pseudonymKeyVersion(), now)) {
            return DeviceGraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
        }
        if (readGrant != null && !readGrant.currentFor(scope, snapshot.pseudonymKeyVersion(), now, requestingPrincipal)) {
            return DeviceGraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
        }
        return null;
    }

    private static DeviceGraphRelationResolution scoreOutcome(SourceOccurrenceAccess.Resolution score) {
        if (score == null) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.SOURCE_UNAVAILABLE); }
        return switch (score.state()) {
            case VERIFIED -> null;
            case PENDING -> DeviceGraphRelationResolution.pending(GraphReadReason.SOURCE_UNAVAILABLE);
            case UNAVAILABLE -> DeviceGraphRelationResolution.unavailable(GraphReadReason.SOURCE_UNAVAILABLE);
            case CONFLICT -> DeviceGraphRelationResolution.conflict();
        };
    }

    private static DeviceGraphRelationResolution ownerOutcome(MongoDeviceObservationStore.GraphOccurrenceRead result) {
        if (result == null) { return DeviceGraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
        return switch (result.state()) {
            case VERIFIED -> throw new IllegalArgumentException("DEVICE_GRAPH_OWNER_OUTCOME_INVALID");
            case PENDING -> DeviceGraphRelationResolution.pending(result.reason());
            case UNAVAILABLE -> DeviceGraphRelationResolution.unavailable(result.reason());
            case DENIED -> DeviceGraphRelationResolution.denied(result.reason());
            case CONFLICT -> DeviceGraphRelationResolution.conflict();
        };
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
                provenance.authorizationCommitment());
        GraphScoredOccurrenceRef scored = new GraphScoredOccurrenceRef(
                pseudonyms.graphReference("score-event", score.scoredEventId()), score.scoredCreatedAt(),
                score.scoredFingerprint(), score.runId());
        return new GraphEvidenceRef(graphScope, occurrence, snapshot.deviceToken(), snapshot.pseudonymKeyVersion(),
                provenance.receiptId(), provenance.rawCommitment(), provenance.enrichedCommitment(), ownerReference,
                coverageReference, policyReference, scored, snapshot.stageASnapshotCommitment(), snapshot.coverageStart(),
                snapshot.eventTime(), snapshot.coverageGeneration(), snapshot.expiresAt());
    }

    private boolean expired(long started) {
        return System.nanoTime() - started > MAX_VALIDATION_NANOS;
    }
}
