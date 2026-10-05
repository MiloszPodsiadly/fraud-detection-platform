package com.frauddetection.alert.consumer;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionMapper;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionWriteFence;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionWriteResult;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AlertServiceEngineIntelligenceProjectionFixtureTest {

    private final EngineIntelligenceProjectionWriteFence writeFence = mock(
            EngineIntelligenceProjectionWriteFence.class,
            invocation -> {
                EngineIntelligenceProjection projection = invocation.getArgument(0);
                return new EngineIntelligenceProjectionWriteResult(
                        EngineIntelligenceProjectionWriteResult.Status.ACCEPTED,
                        projection
                );
            }
    );
    private final EngineIntelligenceProjectionService service = new EngineIntelligenceProjectionService(
            writeFence,
            new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy()),
            new AlertServiceMetrics(new SimpleMeterRegistry()),
            mock(ScoredTransactionRepository.class)
    );

    @Test
    void minimalEngineIntelligenceEventIsProjected() {
        EngineIntelligenceProjection projection = project(
                AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligence()
        );

        assertThat(projection.getContractVersion()).isEqualTo(1);
        assertThat(projection.getEngineCount()).isEqualTo(2);
    }

    @Test
    void fullBoundedEngineIntelligenceEventIsProjected() {
        EngineIntelligenceProjection projection = project(
                AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence()
        );

        assertThat(projection.getEngineCount()).isEqualTo(2);
        assertThat(projection.getDiagnosticSignalCount()).isEqualTo(2);
        assertThat(projection.getWarningCount()).isEqualTo(2);
    }

    @Test
    void currentEngineIntelligenceComparisonIsProjectedWithExplicitIdentity() {
        EngineIntelligenceProjection projection = project(
                AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligence()
        );

        assertThat(projection.getEngineCount()).isEqualTo(2);
        assertThat(projection.getComparisonType().name()).isEqualTo("RULES_VS_ML");
        assertThat(projection.getComparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
    }

    @Test
    void timeoutUnavailableAndDegradedEnginesProjectWithoutRiskLevel() {
        EngineIntelligenceProjection projection = project(
                AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence()
        );

        assertThat(projection.getEngines())
                .filteredOn(engine -> engine.status() != FraudEngineStatus.AVAILABLE)
                .allSatisfy(engine -> assertThat(engine.riskLevel()).isNull());
    }

    @Test
    void operationalSignalProjectsWithoutRiskLevel() {
        EngineIntelligenceProjection projection = project(
                AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence()
        );

        assertThat(projection.getDiagnosticSignals())
                .filteredOn(signal -> signal.engineStatus() != FraudEngineStatus.AVAILABLE)
                .allSatisfy(signal -> assertThat(signal.riskLevel()).isNull());
    }

    @Test
    void unknownNestedFieldsAreIgnored() {
        EngineIntelligenceProjection projection = project(
                AlertServiceTransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFields()
        );

        assertThat(projection.getEngineCount()).isEqualTo(2);
    }

    private EngineIntelligenceProjection project(com.frauddetection.common.events.contract.TransactionScoredEvent event) {
        return service.project(event).projection().orElseThrow();
    }
}
