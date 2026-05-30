package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.engine.ml.PythonMlSignalEngine;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.service.MlFraudScoringEngine;
import com.frauddetection.scoring.service.MlModelScoringClient;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
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
        assertThat(result.generatedAt()).isEqualTo(context().receivedAt());
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
