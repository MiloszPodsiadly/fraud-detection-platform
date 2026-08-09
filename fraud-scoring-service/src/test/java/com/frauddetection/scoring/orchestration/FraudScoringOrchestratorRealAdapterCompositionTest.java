package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.VelocityFeatureContract;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.ml.PythonMlSignalEngine;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalEngine;
import com.frauddetection.scoring.engine.velocity.VelocitySignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationPolicy;
import com.frauddetection.scoring.orchestration.aggregation.FraudEngineAggregationService;
import com.frauddetection.scoring.orchestration.aggregation.PublicEngineIntelligenceMapper;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.MlModelScoringClient;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.context;
import static com.frauddetection.scoring.orchestration.FraudScoringOrchestratorTestSupport.engineIds;
import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringOrchestratorRealAdapterCompositionTest {

    @Test
    void realRuleAndPythonMlAdaptersExecuteTogether() {
        RecordingMlClient mlClient = new RecordingMlClient(availableMlOutput());
        FraudScoringOrchestrationResult result = orchestrator(realRuleEngine(), realMlEngine(mlClient))
                .evaluate(context());

        assertThat(result.engineResults()).hasSize(2);
        assertThat(engineIds(result)).containsExactly("rules.primary", "ml.python.primary");
        assertThat(result.engineResults()).extracting(FraudEngineResult::status)
                .containsExactly(FraudEngineStatus.AVAILABLE, FraudEngineStatus.AVAILABLE);
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.COMPLETE);
        assertThat(result.generatedAt()).isNotEqualTo(context().receivedAt());
        assertThat(result.generatedAt()).isEqualTo(result.engineResults().getLast().generatedAt());
        assertThat(mlClient.calls()).isEqualTo(1);
        assertNoExternalDecisionFields();
    }

    @Test
    void realMlUnavailableDoesNotEraseRealRuleResult() {
        FraudScoringOrchestrationResult result = orchestrator(realRuleEngine(), realMlEngine(new RecordingMlClient(unavailableMlOutput())))
                .evaluate(context());

        assertThat(result.engineResults()).hasSize(2);
        assertThat(engineIds(result)).containsExactly("rules.primary", "ml.python.primary");
        assertThat(result.engineResults()).extracting(FraudEngineResult::status)
                .containsExactly(FraudEngineStatus.AVAILABLE, FraudEngineStatus.UNAVAILABLE);
        assertThat(result.engineResults().get(1).riskLevel()).isNull();
        assertThat(result.engineResults().get(1).toString()).doesNotContain("LOW");
        assertThat(result.status()).isEqualTo(FraudScoringOrchestrationStatus.PARTIAL);
        assertNoExternalDecisionFields();
    }

    @Test
    void realRulesMlBoundaryAndVelocityComposeIntoCanonicalPublicEngineIntelligence() {
        RecordingMlClient mlClient = new RecordingMlClient(availableMlOutput());
        ScoringContext context = velocityReadyContext();
        FraudScoringOrchestrationResult result = orchestrator(realRuleEngine(), realMlEngine(mlClient), realVelocityEngine())
                .evaluate(context);
        FraudScoringOrchestrationResult withoutVelocity = orchestrator(realRuleEngine(), realMlEngine(new RecordingMlClient(availableMlOutput())))
                .evaluate(context);

        assertThat(result.engineResults()).hasSize(3);
        assertThat(engineIds(result)).containsExactlyElementsOf(FraudEngineIdentityContract.engineOrder());
        assertThat(result.engineResults()).extracting(FraudEngineResult::status)
                .containsExactly(FraudEngineStatus.AVAILABLE, FraudEngineStatus.AVAILABLE, FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults()).allSatisfy(engineResult -> {
            assertThat(engineResult.generatedAt()).isNotEqualTo(context.receivedAt());
            assertThat(engineResult.generatedAt()).isBeforeOrEqualTo(result.generatedAt());
            assertThat(engineResult.latencyMs()).isNotNull();
        });

        PublicEngineIntelligenceMapper publicMapper = new PublicEngineIntelligenceMapper();
        FraudEngineAggregationService aggregationService = new FraudEngineAggregationService(
                FraudEngineAggregationPolicy.defaultInternalPolicy()
        );
        EngineIntelligenceSummary summary = publicMapper.map(aggregationService.aggregate(result));
        EngineIntelligenceSummary twoEngineSummary = publicMapper.map(aggregationService.aggregate(withoutVelocity));

        assertThat(summary.contractVersion()).isEqualTo(EngineIntelligenceSummary.CONTRACT_VERSION);
        assertThat(summary.engines()).extracting(engine -> engine.engineId())
                .containsExactlyElementsOf(FraudEngineIdentityContract.engineOrder());
        assertThat(summary.comparison()).isEqualTo(twoEngineSummary.comparison());
        assertThat(summary.diagnosticSignals()).extracting(signal -> signal.engineId())
                .contains(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID);
        assertThat(mlClient.calls()).isEqualTo(1);
    }

    @Test
    void realAdapterDescriptorsDriveRegistryOrder() {
        FraudSignalEngine ml = realMlEngine(new RecordingMlClient(availableMlOutput()));
        FraudSignalEngine rules = realRuleEngine();
        FraudSignalEngineRegistry registry = new FraudSignalEngineRegistry(List.of(ml, rules));

        assertThat(registry.orderedEngines().stream()
                .map(engine -> engine.descriptor().engineId()))
                .containsExactly("rules.primary", "ml.python.primary");
        assertThat(registry.orderedEngines().stream()
                .map(engine -> engine.descriptor().engineType().name()))
                .containsExactly("RULES", "ML_MODEL");
        assertThat(registry.orderedEngines().stream()
                .map(engine -> engine.descriptor().engineLanguage()))
                .containsExactly("java", "python");
    }

    @Test
    void diagnosticRulesDegradationDoesNotStopMlDiagnosticExecution() {
        RecordingMlClient mlClient = new RecordingMlClient(availableMlOutput());
        FraudScoringOrchestrationResult result = orchestrator(realRuleEngine(), realMlEngine(mlClient))
                .evaluate(invalidRulesContext());

        assertThat(result.engineResults()).extracting(FraudEngineResult::status)
                .containsExactly(FraudEngineStatus.DEGRADED, FraudEngineStatus.AVAILABLE);
        assertThat(result.engineResults().getFirst().score()).isNull();
        assertThat(result.engineResults().getFirst().riskLevel()).isNull();
        assertThat(result.engineResults().getFirst().toString()).doesNotContain("P1D");
        assertThat(mlClient.calls()).isEqualTo(1);
    }

    private FraudScoringOrchestrator orchestrator(FraudSignalEngine... engines) {
        return new FraudScoringOrchestrator(new FraudSignalEngineRegistry(List.of(engines)));
    }

    private RuleBasedSignalEngine realRuleEngine() {
        return new RuleBasedSignalEngine(
                new FeatureSnapshotReaderFactory(),
                new RuleBasedFraudScoringEngine(new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED))
        );
    }

    private PythonMlSignalEngine realMlEngine(RecordingMlClient mlClient) {
        return new PythonMlSignalEngine(new MlFraudScoringEngine(
                mlClient,
                new ScoringMetrics(new SimpleMeterRegistry())
        ));
    }

    private VelocitySignalEngine realVelocityEngine() {
        return new VelocitySignalEngine(new FeatureSnapshotReaderFactory());
    }

    private ScoringContext velocityReadyContext() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        Map<String, Object> features = new HashMap<>(event.featureSnapshot());
        features.put(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE, false);
        features.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 6);
        features.put(
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW,
                VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT
        );
        features.put(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("100.00"));
        features.put(
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW,
                VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT
        );
        features.put(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 6.0d);
        TransactionEnrichedEvent eventWithVelocityFeatures = new TransactionEnrichedEvent(
                event.eventId(),
                event.transactionId(),
                event.correlationId(),
                event.customerId(),
                event.accountId(),
                event.createdAt(),
                event.transactionTimestamp(),
                new Money(new BigDecimal("100.00"), "PLN"),
                event.merchantInfo(),
                event.deviceInfo(),
                event.locationInfo(),
                event.customerContext(),
                6,
                VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT,
                new Money(new BigDecimal("100.00"), "PLN"),
                VelocityFeatureContract.CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT,
                6.0d,
                event.merchantFrequency7d(),
                false,
                false,
                false,
                List.of(),
                Map.copyOf(features)
        );
        return new ScoringContext(
                eventWithVelocityFeatures,
                eventWithVelocityFeatures.featureSnapshot(),
                ScoringMode.ML,
                eventWithVelocityFeatures.correlationId(),
                FraudScoringOrchestratorTestSupport.RECEIVED_AT
        );
    }

    private ScoringContext invalidRulesContext() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        Map<String, Object> features = new HashMap<>(event.featureSnapshot());
        features.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 5);
        features.put(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "P1D");
        TransactionEnrichedEvent invalid = new TransactionEnrichedEvent(
                event.eventId(),
                event.transactionId(),
                event.correlationId(),
                event.customerId(),
                event.accountId(),
                event.createdAt(),
                event.transactionTimestamp(),
                event.transactionAmount(),
                event.merchantInfo(),
                event.deviceInfo(),
                event.locationInfo(),
                event.customerContext(),
                event.recentTransactionCount(),
                event.recentTransactionCountWindow(),
                event.recentAmountSum(),
                event.recentAmountSumWindow(),
                event.transactionVelocityPerMinute(),
                event.merchantFrequency7d(),
                event.deviceNovelty(),
                event.countryMismatch(),
                event.proxyOrVpnDetected(),
                event.featureFlags(),
                Map.copyOf(features)
        );
        return new ScoringContext(
                invalid,
                invalid.featureSnapshot(),
                ScoringMode.ML,
                invalid.correlationId(),
                FraudScoringOrchestratorTestSupport.RECEIVED_AT
        );
    }

    private MlModelOutput availableMlOutput() {
        return new MlModelOutput(
                true,
                0.73d,
                RiskLevel.MEDIUM,
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                Instant.parse("2026-05-30T09:59:59Z"),
                List.of(),
                Map.of(),
                Map.of(),
                null
        );
    }

    private MlModelOutput unavailableMlOutput() {
        return new MlModelOutput(
                false,
                0.0d,
                RiskLevel.LOW,
                "python-logistic-fraud-model",
                "unavailable",
                Instant.parse("2026-05-30T09:59:59Z"),
                List.of(),
                Map.of(),
                Map.of(),
                "unavailable"
        );
    }

    private void assertNoExternalDecisionFields() {
        assertThat(List.of(TransactionScoredEvent.class.getRecordComponents()).stream()
                .map(RecordComponent::getName))
                .doesNotContain("engineResults", "orchestrationStatus", "finalDecision", "recommendedAction");
        assertThat(List.of(FraudScoringOrchestrationResult.class.getRecordComponents()).stream()
                .map(RecordComponent::getName))
                .doesNotContain("finalDecision", "finalRisk", "overallRisk", "recommendedAction");
    }

    private static final class RecordingMlClient implements MlModelScoringClient {
        private final MlModelOutput output;
        private int calls;

        private RecordingMlClient(MlModelOutput output) {
            this.output = output;
        }

        @Override
        public MlModelOutput score(MlModelInput input) {
            calls++;
            return output;
        }

        private int calls() {
            return calls;
        }
    }
}
