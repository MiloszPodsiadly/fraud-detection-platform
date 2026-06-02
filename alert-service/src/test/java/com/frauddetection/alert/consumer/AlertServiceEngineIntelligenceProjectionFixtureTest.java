package com.frauddetection.alert.consumer;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionMapper;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertServiceEngineIntelligenceProjectionFixtureTest {

    private final EngineIntelligenceProjectionRepository repository = mock(EngineIntelligenceProjectionRepository.class);
    private final EngineIntelligenceProjectionService service = new EngineIntelligenceProjectionService(
            repository,
            new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy())
    );

    @Test
    void minimalEngineIntelligenceEventIsProjected() {
        EngineIntelligenceProjection projection = project(
                AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligence()
        );

        assertThat(projection.getContractVersion()).isEqualTo(1);
        assertThat(projection.getEngineCount()).isEqualTo(1);
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

        assertThat(projection.getEngineCount()).isEqualTo(1);
    }

    private EngineIntelligenceProjection project(com.frauddetection.common.events.contract.TransactionScoredEvent event) {
        when(repository.findById(event.transactionId())).thenReturn(Optional.empty());
        return service.project(event).projection().orElseThrow();
    }
}
