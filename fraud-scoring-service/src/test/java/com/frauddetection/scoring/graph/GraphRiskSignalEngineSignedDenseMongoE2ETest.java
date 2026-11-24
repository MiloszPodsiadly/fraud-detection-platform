package com.frauddetection.scoring.graph;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.enricher.device.SignedGraphABTestFixture;
import com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade;
import com.frauddetection.enricher.graph.GraphQueryLimits;
import com.frauddetection.enricher.graph.GraphOccurrenceRef;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.frauddetection.scoring.graph.GraphRiskEvidenceRead.Reason;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit slow proof using independently signed Source/Device/Kafka/Mongo owner occurrences. */
@Tag("UNTRUSTED_TEST_ONLY")
class GraphRiskSignalEngineSignedDenseMongoE2ETest {
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final Duration GRAPH_READ_DEADLINE = Duration.ofMillis(4_500);
    private static final Duration CALLER_TOLERANCE = Duration.ofMillis(500);
    private static final Duration WORKER_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    private static final int PRIOR_OCCURRENCES = 100;
    private static final GraphQueryLimits DENSE_GRAPH_READ_LIMITS = new GraphQueryLimits(
            GraphQueryLimits.DEFAULT.maxDepth(), GraphQueryLimits.DEFAULT.maxNodes(),
            GraphQueryLimits.DEFAULT.maxEdges(), GraphQueryLimits.DEFAULT.maxWindow(),
            GraphQueryLimits.DEFAULT.maxHistoryRows(), GraphQueryLimits.DEFAULT.maxPageSize(),
            GRAPH_READ_DEADLINE);

    @Test
    void realSignedHundredPriorHubMeasuresOwnerAndDatabaseCostUnderTheScorerDeadline() throws Exception {
        AtomicLong firstGraphReadNanos = new AtomicLong();
        AtomicLong scorerRevalidationNanos = new AtomicLong();
        AtomicInteger scorerRevalidations = new AtomicInteger();
        List<GraphDeviceMongoVerticalTestFacade.TwoHopRead> graphReads = new CopyOnWriteArrayList<>();

        var evidence = SignedGraphABTestFixture.withLiveDenseSession(PRIOR_OCCURRENCES,
                DENSE_GRAPH_READ_LIMITS, session -> {
            assertThat(session.priorTime()).isBefore(session.rootTime());
            assertThat(session.rootLineage().state()).isEqualTo(
                    com.frauddetection.common.events.source.SourceLineage.State.VERIFIED);
            assertThat(SourceEventCommitment.enriched(session.rootTransaction()))
                    .isEqualTo(session.rootLineage().eventCommitment());
            ScoringContext context = new ScoringContext(session.rootTransaction(),
                    session.rootTransaction().featureSnapshot(), ScoringMode.RULE_BASED,
                    session.rootTransaction().correlationId(), session.rootTime().plusSeconds(6),
                    session.rootLineage());
            session.resetReadMetrics();

            GraphRiskEvidenceAccess access = new GraphRiskEvidenceAccess() {
                @Override public GraphRiskEvidenceRead read(ScoringContext requested) {
                    long started = System.nanoTime();
                    try {
                        return readAuthorizedNow(session, requested, graphReads);
                    } finally {
                        firstGraphReadNanos.set(System.nanoTime() - started);
                    }
                }

                @Override public GraphRiskEvidenceRead requireOccurrence(ScoringContext requested,
                                                                          GraphRiskEvidenceRead previous) {
                    scorerRevalidations.incrementAndGet();
                    long started = System.nanoTime();
                    if (!isExactRootContext(session, requested) || !previous.isBoundTo(requested)) {
                        scorerRevalidationNanos.set(System.nanoTime() - started);
                        return GraphRiskEvidenceRead.denied();
                    }
                    GraphRiskEvidenceRead current;
                    try {
                        current = readAuthorizedNow(session, requested, graphReads);
                    } finally {
                        scorerRevalidationNanos.set(System.nanoTime() - started);
                    }
                    if (current.status() == GraphRiskEvidenceRead.Status.VERIFIED_POSITIVE
                            && (!previous.binding().equals(current.binding())
                            || !previous.witnesses().equals(current.witnesses()))) {
                        return GraphRiskEvidenceRead.denied();
                    }
                    return current;
                }
            };

            long callerNanos;
            FraudSignalEvaluation result;
            try (var engine = new GraphRiskSignalEngine(access, DEADLINE)) {
                long callerStarted = System.nanoTime();
                try {
                    result = engine.evaluate(context);
                } finally {
                    callerNanos = System.nanoTime() - callerStarted;
                }
            }
            assertThat(session.graph().awaitReadDrain(WORKER_DRAIN_TIMEOUT))
                    .as("all signed Graph reads leave the bounded worker before metrics are inspected")
                    .isTrue();
            var ownerPasses = session.graph().ownerValidationMetrics();
            var graphMetrics = session.graph().graphReadMetrics();
            Map<String, Long> ownerOperations = session.ownerReadOperationCounts();
            Map<String, Long> ownerDocuments = session.ownerReadDocumentCounts();
            long measuredOwnerChecks = session.graph().ownerRevalidationCount();

            if (result.status() == FraudEngineStatus.DEGRADED) {
                assertThat(result.reasonCodes()).containsExactly("GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED",
                        GraphRiskSignalEngine.NOT_CALIBRATED_REASON);
                assertThat(callerNanos).isLessThanOrEqualTo(DEADLINE.toNanos());
                assertThat(graphReads).hasSize(2).allSatisfy(graphRead -> {
                    assertThat(graphRead.state()).isEqualTo("VERIFIED_BOUNDED");
                    assertThat(graphRead.reason()).isEqualTo("NONE");
                });
                assertCompleteOwnerPasses(ownerPasses, PRIOR_OCCURRENCES + 1);
                assertThat(measuredOwnerChecks).isEqualTo(404);
                assertThat(ownerPasses.totalChecks()).isEqualTo(measuredOwnerChecks);
                assertThat(scorerRevalidations).hasValue(1);
            } else if (result.status() == FraudEngineStatus.UNAVAILABLE) {
                assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
                assertThat(result.reasonCodes())
                        .as("status=%s statusReason=%s graphReads=%s ownerOutcomes=%s firstGraphReadMs=%.3f scorerRevalidationMs=%.3f",
                                result.status(), result.statusReason(),
                                graphReads.stream().map(read -> read.state() + ":" + read.reason()).toList(),
                                ownerPasses.readTraces().stream().map(trace -> trace.stream()
                                        .map(check -> check.state() + ":" + check.reason()).toList()).toList(),
                                firstGraphReadNanos.get() / 1_000_000d,
                                scorerRevalidationNanos.get() / 1_000_000d)
                        .containsExactly("GRAPH_READ_DEADLINE_EXCEEDED");
                assertThat(result.statusReason()).isEqualTo("GRAPH_READ_DEADLINE_EXCEEDED");
                assertThat(callerNanos).isLessThanOrEqualTo(DEADLINE.plus(CALLER_TOLERANCE).toNanos());
                assertThat(graphReads).isNotEmpty();
                assertThat(ownerPasses.readTraces()).hasSize(graphReads.size());
                assertThat(measuredOwnerChecks).isPositive().isEqualTo(ownerPasses.totalChecks());
                assertThat(graphReads).allSatisfy(graphRead -> assertThat(graphRead.state())
                        .isIn("VERIFIED_BOUNDED", "UNAVAILABLE"));
                List<List<String>> redactedOwnerOutcomes = session.graph().ownerValidationMetrics().readTraces()
                        .stream().map(trace -> trace.stream()
                                .map(check -> check.state() + ":" + check.reason()).toList()).toList();
                assertThat(graphReads).filteredOn(graphRead -> "UNAVAILABLE".equals(graphRead.state()))
                        .as("redacted owner outcomes by read=%s", redactedOwnerOutcomes)
                        .allSatisfy(graphRead -> assertThat(graphRead.reason()).isEqualTo("DEADLINE_EXCEEDED"));
                assertThat(ownerPasses.readTraces()).allSatisfy(trace -> {
                    assertThat(trace).hasSizeLessThanOrEqualTo(2 * (PRIOR_OCCURRENCES + 1));
                    assertThat(trace).allSatisfy(check -> assertThat(check.ownerAssertionReference()).isNotBlank());
                });
                assertOwnerTracePrefixesMatchExpectedCandidates(ownerPasses);
            } else {
                throw new AssertionError("UNEXPECTED_DENSE_ENGINE_STATUS:" + result.status());
            }

            assertThat(result.score()).isNull();
            assertThat(result.riskLevel()).isNull();
            assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
            assertThat(result.contributions()).isEmpty();
            System.out.printf("GRAPH_SIGNED_DENSE priors=%d setup_warmups=2 samples=1 result=%s result_reasons=%s graph_reads=%s owner_trace_sizes=%s caller_ms=%.3f first_graph_read_ms=%.3f scorer_revalidation_ms=%.3f owner_revalidations=%d scorer_revalidations=%d graph_find=%d graph_getMore=%d graph_documents=%d owner_db_commands=%s owner_db_documents=%s graph_deadline_ms=%d scorer_deadline_ms=5000 caller_tolerance_ms=500%n",
                    PRIOR_OCCURRENCES, result.status(),
                    result.reasonCodes(), graphReads.stream().map(read -> read.state() + ":" + read.reason()).toList(),
                    ownerPasses.readTraces().stream().map(List::size).toList(),
                    callerNanos / 1_000_000d,
                    firstGraphReadNanos.get() / 1_000_000d, scorerRevalidationNanos.get() / 1_000_000d,
                    measuredOwnerChecks, scorerRevalidations.get(), graphMetrics.findCommands(),
                    graphMetrics.getMoreCommands(), graphMetrics.documentsReturned(), ownerOperations, ownerDocuments,
                    GRAPH_READ_DEADLINE.toMillis());
        });

        assertThat(evidence.deviceOccurrenceRows()).isEqualTo(PRIOR_OCCURRENCES + 1L);
        assertThat(evidence.scoredOccurrenceRows()).isEqualTo(PRIOR_OCCURRENCES + 1L);
        assertHealthyDenseSetupOrDeadline(evidence.graph().twoHopRead(),
                evidence.graph().initialTwoHopOwnerChecks());
        assertThat(evidence.graph().outageRead().state()).isEqualTo("UNAVAILABLE");
        assertThat(evidence.graph().outageRead().reason()).isEqualTo("OWNER_UNAVAILABLE");
        assertHealthyDenseSetupOrDeadline(evidence.graph().recoveredRead(), List.of());
        assertThat(evidence.graph().conflictRead().state()).isEqualTo("CONFLICT");
        assertThat(evidence.graph().conflictRead().reason()).isEqualTo("CONFLICTING_EVIDENCE");
    }

    private static void assertCompleteOwnerPasses(
            GraphDeviceMongoVerticalTestFacade.OwnerValidationMetrics metrics, int referencesPerPass) {
        assertThat(metrics.expectedPassReferences()).hasSize(referencesPerPass).doesNotHaveDuplicates();
        assertThat(metrics.readTraces()).hasSize(2);
        for (List<GraphDeviceMongoVerticalTestFacade.OwnerCheck> trace : metrics.readTraces()) {
            assertThat(trace).hasSize(2 * referencesPerPass);
            List<GraphDeviceMongoVerticalTestFacade.OwnerCheck> firstPass =
                    trace.subList(0, referencesPerPass);
            List<GraphDeviceMongoVerticalTestFacade.OwnerCheck> secondPass =
                    trace.subList(referencesPerPass, 2 * referencesPerPass);
            assertThat(firstPass).containsExactlyElementsOf(secondPass);
            assertThat(firstPass.stream().map(GraphDeviceMongoVerticalTestFacade.OwnerCheck::ownerAssertionReference)
                    .toList()).containsExactlyElementsOf(metrics.expectedPassReferences());
            assertThat(firstPass.getFirst().root()).isTrue();
            assertThat(firstPass.subList(1, firstPass.size())).allSatisfy(check ->
                    assertThat(check.root()).isFalse());
            Set<String> distinctReferences = new HashSet<>();
            firstPass.forEach(check -> distinctReferences.add(check.ownerAssertionReference()));
            assertThat(distinctReferences).hasSize(referencesPerPass);
        }
    }

    private static void assertOwnerTracePrefixesMatchExpectedCandidates(
            GraphDeviceMongoVerticalTestFacade.OwnerValidationMetrics metrics) {
        List<GraphDeviceMongoVerticalTestFacade.OwnerCheck> expectedPass = metrics.expectedPassReferences().stream()
                .map(reference -> new GraphDeviceMongoVerticalTestFacade.OwnerCheck(reference,
                        reference.equals(metrics.expectedPassReferences().getFirst()), "VERIFIED", "NONE"))
                .toList();
        List<GraphDeviceMongoVerticalTestFacade.OwnerCheck> expectedTrace = new java.util.ArrayList<>(expectedPass);
        expectedTrace.addAll(expectedPass);
        for (List<GraphDeviceMongoVerticalTestFacade.OwnerCheck> actual : metrics.readTraces()) {
            assertThat(actual).isNotEmpty().hasSizeLessThanOrEqualTo(expectedTrace.size());
            for (int index = 0; index < actual.size(); index++) {
                var observed = actual.get(index);
                var expected = expectedTrace.get(index);
                assertThat(observed.ownerAssertionReference()).as("owner reference position %d", index)
                        .isEqualTo(expected.ownerAssertionReference());
                assertThat(observed.root()).as("root/prior classification at position %d", index)
                        .isEqualTo(expected.root());
                if (index == actual.size() - 1) {
                    assertThat(List.of(observed.state(), observed.reason()))
                            .as("terminal owner outcome at position %d", index)
                            .isIn(List.of("VERIFIED", "NONE"),
                                    List.of("UNAVAILABLE", "DEADLINE_EXCEEDED"));
                } else {
                    assertThat(observed.state()).as("owner state at position %d", index).isEqualTo("VERIFIED");
                    assertThat(observed.reason()).as("owner reason at position %d", index).isEqualTo("NONE");
                }
            }
        }
    }

    private static void assertHealthyDenseSetupOrDeadline(
            GraphDeviceMongoVerticalTestFacade.TwoHopRead read,
            List<GraphDeviceMongoVerticalTestFacade.OwnerCheck> ownerChecks) {
        if ("VERIFIED_BOUNDED".equals(read.state())) {
            assertThat(read.reason()).isEqualTo("NONE");
            assertThat(read.rootOccurrence()).isNotNull();
            assertThat(read.priorOccurrence()).isNotNull();
            return;
        }
        assertThat(read.state()).isEqualTo("UNAVAILABLE");
        List<String> ownerTraceTail = ownerChecks.stream()
                .skip(Math.max(0, ownerChecks.size() - 4L))
                .map(check -> check.state() + ":" + check.reason())
                .toList();
        assertThat(read.reason()).as("setup owner checks=%d tail=%s", ownerChecks.size(), ownerTraceTail)
                .isEqualTo("DEADLINE_EXCEEDED");
        assertThat(read.rootOccurrence()).isNull();
        assertThat(read.priorOccurrence()).isNull();
        assertThat(read.pseudonymKeyVersion()).isZero();
    }

    private static GraphRiskEvidenceRead readAuthorizedNow(SignedGraphABTestFixture.LiveSession session,
                                                            ScoringContext context,
                                                            List<GraphDeviceMongoVerticalTestFacade.TwoHopRead> reads) {
        if (!isExactRootContext(session, context)) { return GraphRiskEvidenceRead.denied(); }
        GraphDeviceMongoVerticalTestFacade.TwoHopRead current = session.graph().readCurrent();
        reads.add(current);
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
                    current.diagnosticReadAt()), context, exactOccurrenceIdentity(root));
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
}
