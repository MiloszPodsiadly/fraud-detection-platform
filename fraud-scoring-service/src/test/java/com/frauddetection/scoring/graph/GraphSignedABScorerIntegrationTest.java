package com.frauddetection.scoring.graph;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.enricher.device.SignedGraphABTestFixture;
import com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real signed Source/Device/Kafka/Graph proof with only the final scorer boundary adapted in test scope. */
@Tag("UNTRUSTED_TEST_ONLY")
class GraphSignedABScorerIntegrationTest {
    @Test
    void signedKafkaOccurrencesProduceOneBoundedBRootedWitnessAndOnlyAnInternalDegradedDiagnostic() throws Exception {
        SignedGraphABTestFixture.Evidence evidence = SignedGraphABTestFixture.create();
        GraphDeviceMongoVerticalTestFacade.Result graph = evidence.graph();
        GraphDeviceMongoVerticalTestFacade.TwoHopRead positive = graph.twoHopRead();

        assertThat(evidence.priorTime()).isBefore(evidence.rootTime());
        assertThat(evidence.deviceOccurrenceRows()).isEqualTo(2);
        assertThat(evidence.scoredOccurrenceRows()).isEqualTo(2);
        assertThat(evidence.adapterReconstructions()).isEqualTo(1);
        assertThat(graph.appendStates()).containsExactly("APPENDED", "APPENDED");
        assertThat(graph.replayStates()).containsExactly("EXISTING", "EXISTING");
        assertThat(graph.postChangeReplayStates()).containsExactly("EXISTING", "EXISTING");
        assertThat(graph.replayPreservedEvidence()).isTrue();
        assertThat(graph.persistedRows()).isEqualTo(2);
        assertThat(graph.readOutcomes()).hasSize(2).allSatisfy(read ->
                assertThat(read.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.VERIFIED_BOUNDED));
        assertThat(positive.state()).isEqualTo("VERIFIED_BOUNDED");
        assertThat(positive.reason()).isEqualTo("NONE");
        assertThat(positive.rootTime()).isEqualTo(evidence.rootTime());
        assertThat(positive.priorTime()).isEqualTo(evidence.priorTime());
        assertThat(positive.pseudonymKeyVersion()).isEqualTo(1);
        assertThat(positive.graphAssociationAdmittedAt()).isBeforeOrEqualTo(positive.diagnosticReadAt());
        assertThat(positive.diagnosticReadAt()).isEqualTo(evidence.readAt());

        assertThat(graph.outageRead().state()).isEqualTo("UNAVAILABLE");
        assertThat(graph.outageRead().reason()).isEqualTo("OWNER_UNAVAILABLE");
        assertThat(graph.outageRead().priorTime()).isNull();
        assertThat(graph.recoveredRead().state()).isEqualTo("VERIFIED_BOUNDED");
        assertThat(graph.recoveredRead().priorTime()).isEqualTo(evidence.priorTime());
        assertThat(graph.conflictRead().state()).isEqualTo("CONFLICT");
        assertThat(graph.conflictRead().reason()).isEqualTo("CONFLICTING_EVIDENCE");
        assertThat(graph.conflictRead().priorTime()).isNull();

        ScoringContext context = new ScoringContext(evidence.rootTransaction(), Map.of(), ScoringMode.RULE_BASED,
                evidence.rootCorrelationId(), evidence.readAt());
        GraphRiskEvidenceRead verifiedGraphEvidence = GraphRiskEvidenceRead.verifiedPositive(
                new GraphRiskEvidenceRead.WitnessDescription(positive.rootTime(), positive.priorTime(),
                        positive.graphAssociationAdmittedAt(), positive.diagnosticReadAt()));
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger occurrenceBindings = new AtomicInteger();
        GraphRiskEvidenceAccess testOnlyAdapter = new GraphRiskEvidenceAccess() {
            @Override public GraphRiskEvidenceRead read(ScoringContext current) {
                reads.incrementAndGet();
                return current == context ? verifiedGraphEvidence
                        : GraphRiskEvidenceRead.unavailable(GraphRiskEvidenceRead.Reason.OWNER_UNAVAILABLE);
            }

            @Override public void requireOccurrence(ScoringContext current, GraphRiskEvidenceRead read) {
                occurrenceBindings.incrementAndGet();
                if (current != context || read != verifiedGraphEvidence
                        || !evidence.rootTransactionId().equals(current.transaction().transactionId())
                        || !evidence.rootCorrelationId().equals(current.correlationId())
                        || !evidence.rootTime().equals(current.transaction().transactionTimestamp())) {
                    throw new SecurityException("SIGNED_GRAPH_ROOT_BINDING_MISMATCH");
                }
            }
        };

        byte[] scoredWireBefore = evidence.rootScoredWireBytes();
        var scoredEventBefore = evidence.rootScoredEvent();
        try (GraphRiskSignalEngine engine = new GraphRiskSignalEngine(testOnlyAdapter, Duration.ofSeconds(1))) {
            FraudSignalEvaluation diagnostic = engine.evaluate(context);
            assertThat(diagnostic.status()).isEqualTo(FraudEngineStatus.DEGRADED);
            assertThat(diagnostic.score()).isNull();
            assertThat(diagnostic.riskLevel()).isNull();
            assertThat(diagnostic.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
            assertThat(diagnostic.contributions()).isEmpty();
            assertThat(reads).hasValue(1);
            assertThat(occurrenceBindings).hasValue(1);
            assertThat(diagnostic.toString()).doesNotContain(evidence.rootTransactionId(), evidence.rootCorrelationId());
        }
        assertThat(evidence.rootScoredEvent()).isEqualTo(scoredEventBefore);
        assertThat(evidence.rootScoredWireBytes()).containsExactly(scoredWireBefore);
        assertThat(scoredEventBefore.engineIntelligence()).isNull();
        assertThat(scoredEventBefore.analystRecommendation()).isNull();
        assertThat(scoredEventBefore.alertRecommended()).isFalse();
        assertThat(scoredEventBefore.scoreDetails()).doesNotContainKey("graph");
        assertThat(FraudEngineIdentityContract.engineOrder()).containsExactly(
                "rules.primary", "ml.python.primary", "velocity.primary", "device.primary");
        assertThat(FraudEngineIdentityContract.maxEngineIntelligenceEngines()).isEqualTo(4);
    }
}
