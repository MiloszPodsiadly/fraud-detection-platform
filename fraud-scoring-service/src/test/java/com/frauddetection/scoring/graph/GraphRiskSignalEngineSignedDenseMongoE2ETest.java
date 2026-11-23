package com.frauddetection.scoring.graph;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.enricher.device.SignedGraphABTestFixture;
import com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade;
import com.frauddetection.enricher.graph.GraphOccurrenceRef;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.frauddetection.scoring.graph.GraphRiskDiagnosticPolicy.ReasonCode;
import static com.frauddetection.scoring.graph.GraphRiskEvidenceRead.Reason;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit slow proof using independently signed Source/Device/Kafka/Mongo owner occurrences. */
@Tag("UNTRUSTED_TEST_ONLY")
class GraphRiskSignalEngineSignedDenseMongoE2ETest {
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final int PRIOR_OCCURRENCES = 100;

    @Test
    void realSignedHundredPriorHubMeasuresOwnerAndDatabaseCostUnderTheScorerDeadline() throws Exception {
        AtomicLong firstGraphReadNanos = new AtomicLong();
        AtomicLong scorerRevalidationNanos = new AtomicLong();
        AtomicInteger scorerRevalidations = new AtomicInteger();

        var evidence = SignedGraphABTestFixture.withLiveDenseSession(PRIOR_OCCURRENCES, session -> {
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
                    GraphRiskEvidenceRead result = readAuthorizedNow(session, requested);
                    firstGraphReadNanos.set(System.nanoTime() - started);
                    return result;
                }

                @Override public GraphRiskEvidenceRead requireOccurrence(ScoringContext requested,
                                                                          GraphRiskEvidenceRead previous) {
                    scorerRevalidations.incrementAndGet();
                    long started = System.nanoTime();
                    if (!isExactRootContext(session, requested) || !previous.isBoundTo(requested)) {
                        scorerRevalidationNanos.set(System.nanoTime() - started);
                        return GraphRiskEvidenceRead.denied();
                    }
                    GraphRiskEvidenceRead current = readAuthorizedNow(session, requested);
                    scorerRevalidationNanos.set(System.nanoTime() - started);
                    if (current.status() == GraphRiskEvidenceRead.Status.VERIFIED_POSITIVE
                            && (!previous.binding().equals(current.binding())
                            || !previous.witnesses().equals(current.witnesses()))) {
                        return GraphRiskEvidenceRead.denied();
                    }
                    return current;
                }
            };

            long callerStarted = System.nanoTime();
            FraudSignalEvaluation result;
            try (var engine = new GraphRiskSignalEngine(access, DEADLINE)) {
                result = engine.evaluate(context);
            }
            long callerNanos = System.nanoTime() - callerStarted;
            assertThat(callerNanos).isLessThanOrEqualTo(DEADLINE.toNanos());
            var ownerPasses = session.graph().ownerValidationMetrics();
            var graphMetrics = session.graph().graphReadMetrics();
            Map<String, Long> ownerOperations = session.ownerReadOperationCounts();
            Map<String, Long> ownerDocuments = session.ownerReadDocumentCounts();

            if (result.status() == FraudEngineStatus.DEGRADED) {
                assertThat(result.reasonCodes()).containsExactly("GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED",
                        GraphRiskSignalEngine.NOT_CALIBRATED_REASON);
                assertThat(ownerPasses.selectedRootFirstPass()).isEqualTo(2);
                assertThat(ownerPasses.selectedPriorFirstPass()).isEqualTo(2);
                assertThat(ownerPasses.selectedRootSecondPass()).isEqualTo(2);
                assertThat(ownerPasses.selectedPriorSecondPass()).isEqualTo(2);
                assertThat(session.graph().ownerRevalidationCount()).isEqualTo(8);
                assertThat(scorerRevalidations).hasValue(1);
            } else {
                assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
                assertThat(result.reasonCodes()).isNotEmpty();
                assertThat(result.score()).isNull();
                assertThat(result.riskLevel()).isNull();
                assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
                assertThat(result.contributions()).isEmpty();
            }
            System.out.printf("GRAPH_SIGNED_DENSE priors=%d setup_warmups=2 samples=1 result=%s caller_ms=%.3f first_graph_read_ms=%.3f scorer_revalidation_ms=%.3f owner_revalidations=%d root_first_pass=%d prior_first_pass=%d root_second_pass=%d prior_second_pass=%d scorer_revalidations=%d graph_find=%d graph_getMore=%d graph_documents=%d owner_db_commands=%s owner_db_documents=%s deadline_ms=5000%n",
                    PRIOR_OCCURRENCES, result.status(), callerNanos / 1_000_000d,
                    firstGraphReadNanos.get() / 1_000_000d, scorerRevalidationNanos.get() / 1_000_000d,
                    session.graph().ownerRevalidationCount(), ownerPasses.selectedRootFirstPass(),
                    ownerPasses.selectedPriorFirstPass(), ownerPasses.selectedRootSecondPass(),
                    ownerPasses.selectedPriorSecondPass(), scorerRevalidations.get(), graphMetrics.findCommands(),
                    graphMetrics.getMoreCommands(), graphMetrics.documentsReturned(), ownerOperations, ownerDocuments);
        });

        assertThat(evidence.deviceOccurrenceRows()).isEqualTo(PRIOR_OCCURRENCES + 1L);
        assertThat(evidence.scoredOccurrenceRows()).isEqualTo(PRIOR_OCCURRENCES + 1L);
        assertThat(evidence.graph().twoHopRead().state()).isEqualTo("VERIFIED_BOUNDED");
        assertThat(evidence.graph().conflictRead().state()).isEqualTo("CONFLICT");
    }

    private static GraphRiskEvidenceRead readAuthorizedNow(SignedGraphABTestFixture.LiveSession session,
                                                            ScoringContext context) {
        if (!isExactRootContext(session, context)) { return GraphRiskEvidenceRead.denied(); }
        GraphDeviceMongoVerticalTestFacade.TwoHopRead current = session.graph().readCurrent();
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
