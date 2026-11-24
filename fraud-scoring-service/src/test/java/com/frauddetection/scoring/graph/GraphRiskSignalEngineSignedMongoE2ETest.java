package com.frauddetection.scoring.graph;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.enricher.device.SignedGraphABTestFixture;
import com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade;
import com.frauddetection.enricher.graph.GraphOccurrenceRef;
import com.frauddetection.enricher.graph.GraphIdentityScope;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.frauddetection.scoring.graph.GraphRiskDiagnosticPolicy.ReasonCode;
import static com.frauddetection.scoring.graph.GraphRiskEvidenceRead.Reason;
import static org.assertj.core.api.Assertions.assertThat;

/** Real signed Source/Device/Kafka/Mongo Graph evidence flowing through the actual private scorer candidate. */
@Tag("UNTRUSTED_TEST_ONLY")
class GraphRiskSignalEngineSignedMongoE2ETest {
    private static final Duration ENGINE_DEADLINE = Duration.ofSeconds(5);

    @Test
    void actualScorerReadsFreshSignedGraphEvidenceAndRejectsOwnerConflictInTheSameLiveSession() throws Exception {
        AtomicInteger scorerReads = new AtomicInteger();
        AtomicInteger occurrenceRevalidations = new AtomicInteger();
        AtomicBoolean admitConflictAfterNextRead = new AtomicBoolean();
        AtomicReference<GraphDeviceMongoVerticalTestFacade.TwoHopRead> conflictRevalidationRead =
                new AtomicReference<>();
        AtomicReference<GraphRiskEvidenceRead> initialClockRead = new AtomicReference<>();
        AtomicReference<GraphRiskEvidenceRead> freshClockRead = new AtomicReference<>();
        AtomicReference<byte[]> originalScoredWireBytes = new AtomicReference<>();
        AtomicInteger graphOwnerReadBaseline = new AtomicInteger();
        AtomicInteger graphOwnerReadFinal = new AtomicInteger();

        SignedGraphABTestFixture.Evidence evidence = SignedGraphABTestFixture.withLiveSession(session -> {
            assertThat(session.priorTime()).isBefore(session.rootTime());
            assertThat(session.rootLineage().state()).isEqualTo(com.frauddetection.common.events.source.SourceLineage.State.VERIFIED);
            assertThat(session.priorLineage().state()).isEqualTo(com.frauddetection.common.events.source.SourceLineage.State.VERIFIED);

            ScoringContext exactB = context(session.rootTransaction(), session.rootLineage(), session.rootTime());
            originalScoredWireBytes.set(session.rootScoredWireBytes());
            GraphRiskEvidenceAccess access = new GraphRiskEvidenceAccess() {
                @Override public GraphRiskEvidenceRead read(ScoringContext requested) {
                    scorerReads.incrementAndGet();
                    GraphRiskEvidenceRead current = readAuthorizedNow(session, requested);
                    initialClockRead.set(current);
                    if (admitConflictAfterNextRead.compareAndSet(true, false)) {
                        session.graph().admitSignedConflict();
                    }
                    return current;
                }

                @Override public GraphRiskEvidenceRead requireOccurrence(ScoringContext requested,
                                                                          GraphRiskEvidenceRead previous) {
                    occurrenceRevalidations.incrementAndGet();
                    if (!isExactRootContext(session, requested) || !previous.isBoundTo(requested)) {
                        return GraphRiskEvidenceRead.denied();
                    }
                    session.graph().advanceReaderClockBy(Duration.ofMillis(1));
                    GraphRiskEvidenceRead current = readAuthorizedNow(session, requested, conflictRevalidationRead);
                    freshClockRead.set(current);
                    if (current.status() == GraphRiskEvidenceRead.Status.VERIFIED_POSITIVE
                            && !previous.sameBoundWitnessAtNonRegressingReadTime(current)) {
                        return GraphRiskEvidenceRead.denied();
                    }
                    return current;
                }
            };

            int ownerReadsBefore = session.graph().ownerRevalidationCount();
            graphOwnerReadBaseline.set(ownerReadsBefore);
            FraudSignalEvaluation first;
            try (var engine = new GraphRiskSignalEngine(access, ENGINE_DEADLINE)) {
                first = engine.evaluate(exactB);
                assertObserved(first);
                assertThat(scorerReads).hasValue(1);
                assertThat(occurrenceRevalidations).hasValue(1);
                assertThat(session.graph().ownerRevalidationCount()).isGreaterThan(ownerReadsBefore);
                assertThat(initialClockRead.get().witnesses().getFirst().diagnosticReadAt())
                        .isNotEqualTo(freshClockRead.get().witnesses().getFirst().diagnosticReadAt());
                assertThat(initialClockRead.get().witnesses().getFirst().stableWitnessCommitment())
                        .isEqualTo(freshClockRead.get().witnesses().getFirst().stableWitnessCommitment());
                assertThat(initialClockRead.get().sameBoundWitnessAtNonRegressingReadTime(freshClockRead.get()))
                        .isTrue();

                GraphRiskEvidenceRead exactRead = readAuthorizedNow(session, exactB);
                assertThat(exactRead.isBoundTo(exactB)).isTrue();
                ScoringContext alternateSourceScope = new ScoringContext(session.rootTransaction(),
                        session.rootTransaction().featureSnapshot(), ScoringMode.RULE_BASED,
                        session.rootTransaction().correlationId(), exactB.receivedAt(),
                        session.alternateRootLineage());
                assertThat(SourceEventCommitment.enriched(alternateSourceScope.transaction()))
                        .isEqualTo(SourceEventCommitment.enriched(exactB.transaction()));
                assertThat(alternateSourceScope.sourceLineage().source())
                        .isNotEqualTo(exactB.sourceLineage().source());
                assertThat(alternateSourceScope.sourceLineage().receiptId())
                        .isNotEqualTo(exactB.sourceLineage().receiptId());
                assertThat(exactRead.isBoundTo(alternateSourceScope)).isFalse();
                GraphRiskEvidenceAccess transferredSourcePositive = ignored -> exactRead;
                try (var transferEngine = new GraphRiskSignalEngine(transferredSourcePositive, ENGINE_DEADLINE)) {
                    assertUnavailable(transferEngine.evaluate(alternateSourceScope), ReasonCode.GRAPH_AUTHORITY_DENIED);
                }
                GraphRiskEvidenceAccess sourceScopeOwnerCheck = new GraphRiskEvidenceAccess() {
                    @Override public GraphRiskEvidenceRead read(ScoringContext requested) {
                        return readAuthorizedNow(session, requested);
                    }
                    @Override public GraphRiskEvidenceRead requireOccurrence(ScoringContext requested,
                                                                              GraphRiskEvidenceRead previous) {
                        return readAuthorizedNow(session, requested);
                    }
                };
                try (var scopeEngine = new GraphRiskSignalEngine(sourceScopeOwnerCheck, ENGINE_DEADLINE)) {
                    assertUnavailable(scopeEngine.evaluate(alternateSourceScope), ReasonCode.GRAPH_AUTHORITY_DENIED);
                }
                assertThat(exactRead.isBoundTo(new ScoringContext(session.rootTransaction(),
                        session.rootTransaction().featureSnapshot(), ScoringMode.SHADOW,
                        session.rootTransaction().correlationId(), exactB.receivedAt(), session.rootLineage()))).isFalse();
                assertThat(exactRead.isBoundTo(new ScoringContext(session.rootTransaction(),
                        session.rootTransaction().featureSnapshot(), ScoringMode.RULE_BASED,
                        "different-correlation", exactB.receivedAt(), session.rootLineage()))).isFalse();
                assertThat(exactRead.isBoundTo(new ScoringContext(session.rootTransaction(),
                        session.rootTransaction().featureSnapshot(), ScoringMode.RULE_BASED,
                        session.rootTransaction().correlationId(), exactB.receivedAt().plusMillis(1),
                        session.rootLineage()))).isFalse();
                assertThat(exactRead.isBoundTo(new ScoringContext(session.rootTransaction(),
                        Map.of("changed-feature", 1), ScoringMode.RULE_BASED,
                        session.rootTransaction().correlationId(), exactB.receivedAt(), session.rootLineage()))).isFalse();
                assertThat(exactRead.isBoundTo(new ScoringContext(session.rootTransaction(),
                        session.rootTransaction().featureSnapshot(), ScoringMode.RULE_BASED,
                        session.rootTransaction().correlationId(), exactB.receivedAt(),
                        com.frauddetection.common.events.source.SourceLineage.unavailable(
                                com.frauddetection.common.events.source.SourceLineage.State.UNVERIFIED)))).isFalse();

                var rootOccurrence = session.rootOccurrence();
                var otherScope = new GraphIdentityScope("0".repeat(64), rootOccurrence.scope().institutionToken(),
                        rootOccurrence.scope().sourceToken(), rootOccurrence.scope().sourceEpochToken(),
                        rootOccurrence.scope().identityVersion());
                var otherOccurrence = new GraphOccurrenceRef(otherScope, rootOccurrence.occurrenceToken(),
                        rootOccurrence.eventTime());
                GraphRiskEvidenceRead transferredOccurrence = GraphRiskEvidenceRead.verifiedPositive(
                        exactRead.witnesses().getFirst(), exactB, exactOccurrenceIdentity(otherOccurrence));
                assertThat(access.requireOccurrence(exactB, transferredOccurrence).status())
                        .isEqualTo(GraphRiskEvidenceRead.Status.DENIED);

                GraphRiskEvidenceRead alteredWitness = GraphRiskEvidenceRead.verifiedPositive(
                        new GraphRiskEvidenceRead.WitnessDescription(session.rootTime(),
                                session.priorTime().minusSeconds(1), exactRead.witnesses().getFirst().graphAssociationAdmittedAt(),
                                exactRead.witnesses().getFirst().diagnosticReadAt(),
                                exactRead.witnesses().getFirst().stableWitnessCommitment()), exactB,
                        exactOccurrenceIdentity(rootOccurrence));
                GraphRiskEvidenceAccess witnessDrift = new GraphRiskEvidenceAccess() {
                    @Override public GraphRiskEvidenceRead read(ScoringContext requested) { return alteredWitness; }
                    @Override public GraphRiskEvidenceRead requireOccurrence(ScoringContext requested,
                                                                              GraphRiskEvidenceRead previous) {
                        return readAuthorizedNow(session, requested);
                    }
                };
                try (var witnessEngine = new GraphRiskSignalEngine(witnessDrift, ENGINE_DEADLINE)) {
                    assertUnavailable(witnessEngine.evaluate(exactB), ReasonCode.GRAPH_AUTHORITY_DENIED);
                }

                var unrelatedPrincipal = session.graph().readAs("unrelated_graph_principal");
                assertThat(unrelatedPrincipal.state()).isEqualTo("DENIED");
                assertThat(unrelatedPrincipal.rootOccurrence()).isNull();

                ScoringContext unrelatedOccurrence = context(session.priorTransaction(), session.priorLineage(),
                        session.rootTime());
                assertThat(access.read(unrelatedOccurrence).status()).isEqualTo(GraphRiskEvidenceRead.Status.DENIED);
                assertThat(scorerReads).hasValue(2);

                admitConflictAfterNextRead.set(true);
                FraudSignalEvaluation conflictDuringRevalidation = engine.evaluate(exactB);
                assertThat(conflictDuringRevalidation.reasonCodes())
                        .as("typed result after signed mutation; current Graph read=%s", conflictRevalidationRead.get())
                        .containsExactly(ReasonCode.GRAPH_OWNER_CONFLICT.wireValue());
                assertThat(conflictRevalidationRead.get()).satisfies(current -> {
                    assertThat(current.state()).isEqualTo("CONFLICT");
                    assertThat(current.reason()).isEqualTo("CONFLICTING_EVIDENCE");
                    assertThat(current.rootOccurrence()).isNull();
                    assertThat(current.priorOccurrence()).isNull();
                });
            }

            int readsBeforeForgery = scorerReads.get();
            int revalidationsBeforeForgery = occurrenceRevalidations.get();
            GraphRiskEvidenceRead forgedPositive = GraphRiskEvidenceRead.verifiedPositive(
                    new GraphRiskEvidenceRead.WitnessDescription(session.rootTime(), session.priorTime(),
                            session.rootTime().plusSeconds(1), session.rootTime().plusSeconds(6)));
            try (var malicious = new GraphRiskSignalEngine(ignored -> forgedPositive, ENGINE_DEADLINE)) {
                FraudSignalEvaluation rejected = malicious.evaluate(exactB);
                assertUnavailable(rejected, ReasonCode.GRAPH_AUTHORITY_DENIED);
            }
            assertThat(scorerReads).hasValue(readsBeforeForgery);
            assertThat(occurrenceRevalidations).hasValue(revalidationsBeforeForgery);
            graphOwnerReadFinal.set(session.graph().ownerRevalidationCount());

            assertThat(session.rootScoredEvent().engineIntelligence()).isNull();
            assertThat(session.rootScoredEvent().analystRecommendation()).isNull();
            System.out.printf("GRAPH_SIGNED_E2E scorer_reads=%d scorer_revalidations=%d graph_owner_revalidations=%d source_mutation=SIGNED_CONFLICT final_owner_state=CONFLICT%n",
                    scorerReads.get(), occurrenceRevalidations.get(), graphOwnerReadFinal.get());
        });

        assertThat(evidence.graph().appendStates()).containsExactly("APPENDED", "APPENDED");
        assertThat(evidence.graph().replayStates()).containsExactly("EXISTING", "EXISTING");
        assertThat(evidence.graph().replayPreservedEvidence()).isTrue();
        assertThat(evidence.graph().twoHopRead().state()).isEqualTo("VERIFIED_BOUNDED");
        assertThat(evidence.graph().conflictRead().state()).isEqualTo("CONFLICT");
        assertThat(evidence.rootScoredEvent().engineIntelligence()).isNull();
        assertThat(evidence.rootScoredEvent().analystRecommendation()).isNull();
        assertThat(evidence.rootScoredWireBytes()).isEqualTo(originalScoredWireBytes.get());
        assertThat(graphOwnerReadFinal.get()).isGreaterThan(graphOwnerReadBaseline.get());
        assertThat(scorerReads).hasValue(3);
        assertThat(occurrenceRevalidations).hasValue(3);
    }

    private static GraphRiskEvidenceRead readAuthorizedNow(SignedGraphABTestFixture.LiveSession session,
                                                            ScoringContext context) {
        return readAuthorizedNow(session, context, null);
    }

    private static GraphRiskEvidenceRead readAuthorizedNow(SignedGraphABTestFixture.LiveSession session,
                                                            ScoringContext context,
                                                            AtomicReference<GraphDeviceMongoVerticalTestFacade.TwoHopRead> capture) {
        if (!isExactRootContext(session, context)) { return GraphRiskEvidenceRead.denied(); }
        GraphDeviceMongoVerticalTestFacade.TwoHopRead current = session.graph().readCurrent();
        if (capture != null) { capture.set(current); }
        if ("VERIFIED_BOUNDED".equals(current.state())) {
            GraphOccurrenceRef root = current.rootOccurrence();
            GraphOccurrenceRef expected = session.rootOccurrence();
            if (root == null || !root.equals(expected) || !root.scope().equals(expected.scope())
                    || !root.eventTime().equals(context.transaction().transactionTimestamp())
                    || current.priorOccurrence() == null
                    || !current.priorOccurrence().eventTime().equals(session.priorTime())
                    || !current.priorOccurrence().scope().equals(root.scope())
                    || current.pseudonymKeyVersion() != 1) {
                return GraphRiskEvidenceRead.denied();
            }
            return GraphRiskEvidenceRead.verifiedPositive(new GraphRiskEvidenceRead.WitnessDescription(
                    current.rootTime(), current.priorTime(), current.graphAssociationAdmittedAt(),
                    current.diagnosticReadAt(), current.stableWitnessCommitment()), context,
                    exactOccurrenceIdentity(root));
        }
        return switch (current.state()) {
            case "CONFLICT" -> GraphRiskEvidenceRead.conflict();
            case "DENIED" -> GraphRiskEvidenceRead.denied();
            case "PENDING" -> GraphRiskEvidenceRead.pending(Reason.OWNER_PENDING);
            default -> "TRUNCATED_UNVERIFIED".equals(current.reason())
                    ? GraphRiskEvidenceRead.truncated()
                    : GraphRiskEvidenceRead.unavailable("DEADLINE_EXCEEDED".equals(current.reason())
                    ? Reason.DEADLINE_EXCEEDED : Reason.OWNER_UNAVAILABLE);
        };
    }

    private static boolean isExactRootContext(SignedGraphABTestFixture.LiveSession session,
                                              ScoringContext context) {
        if (context == null || !session.rootTransaction().equals(context.transaction())
                || context.sourceLineage().state() != com.frauddetection.common.events.source.SourceLineage.State.VERIFIED) {
            return false;
        }
        try {
            return SourceEventCommitment.enriched(context.transaction())
                    .equals(context.sourceLineage().eventCommitment())
                    && context.sourceLineage().eventCommitment().equals(session.rootLineage().eventCommitment())
                    && context.sourceLineage().source().equals(session.rootLineage().source())
                    && context.sourceLineage().receiptId().equals(session.rootLineage().receiptId());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String exactOccurrenceIdentity(GraphOccurrenceRef occurrence) {
        var scope = occurrence.scope();
        return String.join("\u001f", scope.sourceIssuerToken(), scope.institutionToken(), scope.sourceToken(),
                scope.sourceEpochToken(), Integer.toString(scope.identityVersion()), occurrence.occurrenceToken(),
                occurrence.eventTime().toString());
    }

    private static ScoringContext context(TransactionEnrichedEvent transaction,
                                          com.frauddetection.common.events.source.SourceLineage sourceLineage,
                                          java.time.Instant receivedAt) {
        return new ScoringContext(transaction, transaction.featureSnapshot(), ScoringMode.RULE_BASED,
                transaction.correlationId(), receivedAt.plusSeconds(6), sourceLineage);
    }

    private static void assertObserved(FraudSignalEvaluation evaluation) {
        assertThat(evaluation.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(evaluation.reasonCodes()).containsExactly("GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED",
                GraphRiskSignalEngine.NOT_CALIBRATED_REASON);
        assertThat(evaluation.statusReason()).isEqualTo(GraphRiskSignalEngine.NOT_CALIBRATED_REASON);
        assertThat(evaluation.score()).isNull();
        assertThat(evaluation.riskLevel()).isNull();
        assertThat(evaluation.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(evaluation.contributions()).isEmpty();
        assertThat(evaluation.toString()).doesNotContain("customer-1", "graph-e2e-device",
                "graph-e2e-transaction-B", "graph-e2e-score-B");
    }

    private static void assertUnavailable(FraudSignalEvaluation evaluation, ReasonCode reason) {
        assertThat(evaluation.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(evaluation.reasonCodes()).containsExactly(reason.wireValue());
        assertThat(evaluation.score()).isNull();
        assertThat(evaluation.riskLevel()).isNull();
        assertThat(evaluation.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(evaluation.contributions()).isEmpty();
    }
}
