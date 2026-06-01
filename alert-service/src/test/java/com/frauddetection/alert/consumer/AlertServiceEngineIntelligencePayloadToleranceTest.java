package com.frauddetection.alert.consumer;

import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceEngineIntelligencePayloadToleranceTest {

    private static final int SAFE_FIXTURE_THRESHOLD_BYTES = 8 * 1024;
    private final ScoredTransactionDocumentMapper mapper = new ScoredTransactionDocumentMapper();

    @Test
    void alertServiceToleratesFullBoundedPayloadWithoutProjectionExpansion() {
        String fixture = AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligenceJson();
        TransactionScoredEvent full = AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence();

        assertThat(fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThan(SAFE_FIXTURE_THRESHOLD_BYTES);
        assertThat(full.engineIntelligence()).isNotNull();
        assertThat(mapper.toDocument(full))
                .usingRecursiveComparison()
                .isEqualTo(mapper.toDocument(AlertServiceTransactionScoredEventFixtureLoader.oldWithoutEngineIntelligence()));
        assertThat(full.engineIntelligence().toString())
                .doesNotContainIgnoringCase("rawPayload", "rawEvidence", "finalDecision", "recommendedAction");
    }

    @Test
    void fullBoundedFixtureRespectsPublicContractSemanticLimits() {
        EngineIntelligenceSummary engineIntelligence =
                AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence().engineIntelligence();

        assertThat(engineIntelligence.engines()).hasSizeLessThanOrEqualTo(2);
        assertThat(engineIntelligence.diagnosticSignals()).hasSizeLessThanOrEqualTo(5);
        assertThat(engineIntelligence.warnings()).hasSizeLessThanOrEqualTo(10);
        assertThat(engineIntelligence.engines()).allSatisfy(engine -> {
            assertThat(engine.reasonCodes()).hasSizeLessThanOrEqualTo(5);
            if (engine.status() != FraudEngineStatus.AVAILABLE) {
                assertThat(engine.riskLevel()).isNull();
            }
        });
        assertThat(engineIntelligence.diagnosticSignals())
                .filteredOn(signal -> signal.engineStatus() != FraudEngineStatus.AVAILABLE)
                .allSatisfy(signal -> assertThat(signal.riskLevel()).isNull());
        assertThat(engineIntelligence.diagnosticSignals())
                .filteredOn(signal -> signal.signalCategory() == EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL)
                .allSatisfy(signal -> {
                    assertThat(signal.riskLevel()).isNull();
                    assertThat(signal.scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
                });
    }
}
