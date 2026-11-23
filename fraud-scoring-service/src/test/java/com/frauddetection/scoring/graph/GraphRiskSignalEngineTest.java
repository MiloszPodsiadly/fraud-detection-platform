package com.frauddetection.scoring.graph;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import com.frauddetection.scoring.orchestration.runtime.BoundedFraudEngineExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.frauddetection.scoring.graph.GraphRiskDiagnosticPolicy.ReasonCode;
import static com.frauddetection.scoring.graph.GraphRiskEvidenceRead.Reason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Candidate unit tests use controlled access doubles; they do not claim signed end-to-end proof. */
class GraphRiskSignalEngineTest {
    private static final Instant PRIOR = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant ROOT = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant GRAPH_ADMITTED = ROOT.plusSeconds(60);
    private static final Instant READ_AT = Instant.parse("2026-10-03T10:00:00Z");
    private static final Duration DEADLINE = Duration.ofSeconds(1);

    @Test
    void candidateDescriptorIsOptionalButPublicEiV2AndRegistryStillRejectIt() {
        try (var engine = new GraphRiskSignalEngine()) {
            assertThat(engine.descriptor().engineId()).isEqualTo("graph.primary");
            assertThat(engine.descriptor().engineType()).isEqualTo(FraudEngineType.GRAPH_RISK);
            assertThat(engine.descriptor().engineLanguage()).isEqualTo("java");
            assertThat(engine.descriptor().version()).isEqualTo("graph-v1");
            assertThat(engine.descriptor().required()).isFalse();
            assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(engine)))
                    .hasMessage("ENGINE_REGISTRY_UNKNOWN_ENGINE_ID");
            assertThatThrownBy(() -> new EngineIntelligenceEngineResult("graph.primary", FraudEngineType.GRAPH_RISK,
                    FraudEngineStatus.UNAVAILABLE, null, EngineIntelligenceScoreBucket.UNAVAILABLE, List.of()))
                    .hasMessage("ENGINE_INTELLIGENCE_UNKNOWN_ENGINE_ID");
        }
        assertThat(FraudEngineIdentityContract.engineOrder())
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary", "device.primary");
        assertThat(FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION).isEqualTo(2);
        assertThat(FraudEngineIdentityContract.maxEngineIntelligenceEngines()).isEqualTo(4);
        assertThat(FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_SERIALIZED_BYTES).isEqualTo(16_384);
    }

    @Test
    void defaultCandidateHasNoProviderAndAlwaysReturnsNonRiskUnavailableResult() {
        try (var engine = new GraphRiskSignalEngine()) {
            FraudSignalEvaluation result = engine.evaluate(context());

            assertUnavailable(result, ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED);
            assertThat(result.evidence()).singleElement().satisfies(evidence -> {
                assertThat(evidence.evidenceType()).isEqualTo(FraudEngineEvidenceType.GRAPH_SIGNAL);
                assertThat(evidence.status()).isEqualTo(FraudEngineEvidenceStatus.UNAVAILABLE);
            });
        }
    }

    @Test
    void independentlyBoundPositiveIsDegradedAndNeverContributesScoreOrRisk() {
        ScoringContext context = context();
        GraphRiskEvidenceRead read = GraphRiskEvidenceRead.verifiedPositive(witness());
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger occurrenceChecks = new AtomicInteger();
        GraphRiskEvidenceAccess access = new GraphRiskEvidenceAccess() {
            @Override public GraphRiskEvidenceRead read(ScoringContext current) {
                reads.incrementAndGet();
                return read;
            }

            @Override public GraphRiskEvidenceRead requireOccurrence(ScoringContext current,
                                                                      GraphRiskEvidenceRead evidence) {
                occurrenceChecks.incrementAndGet();
                if (current != context || evidence != read) { return GraphRiskEvidenceRead.denied(); }
                return evidence;
            }
        };

        try (var engine = candidate(access, DEADLINE)) {
            FraudSignalEvaluation result = engine.evaluate(context);

            assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
            assertEnvelope(result);
            assertThat(result.reasonCodes()).containsExactly("GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED",
                    GraphRiskSignalEngine.NOT_CALIBRATED_REASON);
            assertThat(result.statusReason()).isEqualTo(GraphRiskSignalEngine.NOT_CALIBRATED_REASON);
            assertThat(result.evidence()).singleElement().satisfies(evidence -> {
                assertThat(evidence.evidenceType()).isEqualTo(FraudEngineEvidenceType.GRAPH_SIGNAL);
                assertThat(evidence.reasonCode()).isEqualTo("GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED");
                assertThat(evidence.status()).isEqualTo(FraudEngineEvidenceStatus.AVAILABLE);
                assertThat(evidence.description()).contains("retrospective").doesNotContain("fraud", "customer", "physical device");
            });
            assertThat(reads).hasValue(1);
            assertThat(occurrenceChecks).hasValue(1);
            assertThat(result.toString()).doesNotContain(context.transaction().customerId(), context.correlationId());
        }
    }

    @Test
    void anUnboundPositiveDescriptionIsDeniedAndNeverReusesEvidence() {
        ScoringContext context = context();
        GraphRiskEvidenceRead positive = GraphRiskEvidenceRead.verifiedPositive(witness());
        GraphRiskEvidenceAccess unbound = current -> positive;
        try (var engine = candidate(unbound, DEADLINE)) {
            assertUnavailable(engine.evaluate(context), ReasonCode.GRAPH_AUTHORITY_DENIED);
        }

        AtomicInteger reads = new AtomicInteger();
        GraphRiskEvidenceAccess changesBetweenCalls = new GraphRiskEvidenceAccess() {
            @Override public GraphRiskEvidenceRead read(ScoringContext current) {
                return reads.getAndIncrement() == 0 ? positive
                        : GraphRiskEvidenceRead.unavailable(Reason.OWNER_UNAVAILABLE);
            }
            @Override public GraphRiskEvidenceRead requireOccurrence(ScoringContext current,
                                                                      GraphRiskEvidenceRead evidence) {
                return evidence;
            }
        };
        try (var engine = candidate(changesBetweenCalls, DEADLINE)) {
            assertThat(engine.evaluate(context).status()).isEqualTo(FraudEngineStatus.DEGRADED);
            FraudSignalEvaluation freshFailure = engine.evaluate(context);
            assertUnavailable(freshFailure, ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE);
            assertThat(freshFailure.evidence()).allSatisfy(evidence ->
                    assertThat(evidence.status()).isEqualTo(FraudEngineEvidenceStatus.UNAVAILABLE));
            assertThat(reads).hasValue(2);
        }
    }

    @Test
    void freshConflictBetweenInitialReadAndOccurrenceBindingInvalidatesThePositive() {
        ScoringContext context = context();
        GraphRiskEvidenceRead stalePositive = GraphRiskEvidenceRead.verifiedPositive(witness());
        AtomicInteger bindingChecks = new AtomicInteger();
        GraphRiskEvidenceAccess conflictBetweenChecks = new GraphRiskEvidenceAccess() {
            @Override public GraphRiskEvidenceRead read(ScoringContext current) { return stalePositive; }

            @Override public GraphRiskEvidenceRead requireOccurrence(ScoringContext current,
                                                                      GraphRiskEvidenceRead candidate) {
                bindingChecks.incrementAndGet();
                assertThat(current).isSameAs(context);
                assertThat(candidate).isSameAs(stalePositive);
                return GraphRiskEvidenceRead.conflict();
            }
        };

        try (var engine = candidate(conflictBetweenChecks, DEADLINE)) {
            FraudSignalEvaluation result = engine.evaluate(context);

            assertUnavailable(result, ReasonCode.GRAPH_OWNER_CONFLICT);
            assertThat(bindingChecks).hasValue(1);
        }
    }

    @Test
    void everyNonPositiveAuthorityStatusStaysUnavailableWithItsTypedReason() {
        assertReadUnavailable(GraphRiskEvidenceRead.pending(Reason.OWNER_PENDING),
                ReasonCode.GRAPH_OWNER_EVIDENCE_PENDING);
        assertReadUnavailable(GraphRiskEvidenceRead.unavailable(Reason.OWNER_UNAVAILABLE),
                ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE);
        assertReadUnavailable(GraphRiskEvidenceRead.unavailable(Reason.DEADLINE_EXCEEDED),
                ReasonCode.GRAPH_READ_DEADLINE_EXCEEDED);
        assertReadUnavailable(GraphRiskEvidenceRead.denied(), ReasonCode.GRAPH_AUTHORITY_DENIED);
        assertReadUnavailable(GraphRiskEvidenceRead.conflict(), ReasonCode.GRAPH_OWNER_CONFLICT);
        assertReadUnavailable(GraphRiskEvidenceRead.truncated(), ReasonCode.GRAPH_CANDIDATE_SET_INCOMPLETE);
    }

    @Test
    void providerFailuresAreSanitizedAndKeepDenialDistinctFromUnavailable() {
        String secret = "private-owner-path-and-token";
        try (var denied = candidate(current -> { throw new SecurityException(secret); }, DEADLINE)) {
            FraudSignalEvaluation result = denied.evaluate(context());
            assertUnavailable(result, ReasonCode.GRAPH_AUTHORITY_DENIED);
            assertThat(result.toString()).doesNotContain(secret);
        }
        try (var unavailable = candidate(current -> { throw new IllegalStateException(secret); }, DEADLINE)) {
            FraudSignalEvaluation result = unavailable.evaluate(context());
            assertUnavailable(result, ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE);
            assertThat(result.toString()).doesNotContain(secret);
        }
        try (var failedWorker = candidate(current -> { throw new AssertionError(secret); }, DEADLINE)) {
            FraudSignalEvaluation result = failedWorker.evaluate(context());
            assertUnavailable(result, ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE);
            assertThat(result.toString()).doesNotContain(secret);
        }
    }

    @Test
    void timedOutReadIsCancelledAndReturnsUnavailable() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        GraphRiskEvidenceAccess blocking = current -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancelled) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return GraphRiskEvidenceRead.verifiedPositive(witness());
        };
        try (var engine = candidate(blocking, Duration.ofMillis(150))) {
            FraudSignalEvaluation result = engine.evaluate(context());
            assertUnavailable(result, ReasonCode.GRAPH_READ_DEADLINE_EXCEEDED);
            assertThat(entered.getCount()).isZero();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void boundedExecutorRejectsSaturatedWorkAndCloseIsIdempotent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        GraphRiskEvidenceAccess nonCooperating = current -> {
            reads.incrementAndGet();
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Deliberately models a dependency ignoring cancellation. */ }
            }
            return GraphRiskEvidenceRead.unavailable(Reason.OWNER_UNAVAILABLE);
        };
        BoundedFraudEngineExecutor executor = new BoundedFraudEngineExecutor(1, 1);
        GraphRiskSignalEngine engine = new GraphRiskSignalEngine(nonCooperating, Duration.ofMillis(250), executor);
        try {
            assertUnavailable(engine.evaluate(context()), ReasonCode.GRAPH_READ_DEADLINE_EXCEEDED);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertUnavailable(engine.evaluate(context()), ReasonCode.GRAPH_READ_DEADLINE_EXCEEDED);
            assertUnavailable(engine.evaluate(context()), ReasonCode.GRAPH_ACCESS_REJECTED);
            assertThat(reads).hasValue(1);
        } finally {
            release.countDown();
            engine.close();
            engine.close();
        }
        assertUnavailable(engine.evaluate(context()), ReasonCode.GRAPH_ACCESS_REJECTED);
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(6))) {
            assertThatThrownBy(() -> new GraphRiskSignalEngine(current -> GraphRiskEvidenceRead.unavailable(
                    Reason.OWNER_UNAVAILABLE), invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void normalComponentScanCannotDiscoverThePackagePrivateCandidate() {
        new ApplicationContextRunner().withUserConfiguration(GraphCandidateComponentScan.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GraphRiskSignalEngine.class);
                });
    }

    private static void assertReadUnavailable(GraphRiskEvidenceRead read, ReasonCode reason) {
        try (var engine = candidate(current -> read, DEADLINE)) {
            assertUnavailable(engine.evaluate(context()), reason);
        }
    }

    private static void assertUnavailable(FraudSignalEvaluation result, ReasonCode reason) {
        assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertEnvelope(result);
        assertThat(result.reasonCodes()).containsExactly(reason.wireValue());
        assertThat(result.statusReason()).isEqualTo(reason.wireValue());
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.evidenceType()).isEqualTo(FraudEngineEvidenceType.GRAPH_SIGNAL);
            assertThat(evidence.status()).isEqualTo(FraudEngineEvidenceStatus.UNAVAILABLE);
            assertThat(evidence.reasonCode()).isEqualTo(reason.wireValue());
        });
    }

    private static void assertEnvelope(FraudSignalEvaluation result) {
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.contributions()).isEmpty();
    }

    private static GraphRiskSignalEngine candidate(GraphRiskEvidenceAccess access, Duration deadline) {
        return new GraphRiskSignalEngine(access, deadline);
    }

    private static ScoringContext context() {
        TransactionEnrichedEvent transaction = TransactionFixtures.enrichedTransaction().build();
        return new ScoringContext(transaction, Map.of(), ScoringMode.RULE_BASED,
                transaction.correlationId(), READ_AT);
    }

    private static GraphRiskEvidenceRead.WitnessDescription witness() {
        return new GraphRiskEvidenceRead.WitnessDescription(ROOT, PRIOR, GRAPH_ADMITTED, READ_AT);
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.frauddetection.scoring.graph")
    static class GraphCandidateComponentScan { }
}
