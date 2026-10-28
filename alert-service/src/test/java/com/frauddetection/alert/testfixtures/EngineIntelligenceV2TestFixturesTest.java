package com.frauddetection.alert.testfixtures;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlModelIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceV2TestFixturesTest {
    private final EngineIntelligenceResponseMapper responseMapper = new EngineIntelligenceResponseMapper();

    @Test
    void timeoutScenarioHasMatchingCanonicalSummaryAndReadModelSemantics() {
        EngineIntelligenceSummary summary = EngineIntelligenceV2TestFixtures.rulesCriticalMlTimeoutSummary();
        EngineIntelligenceReadModel readModel = EngineIntelligenceV2TestFixtures
                .rulesCriticalMlTimeoutReadModel("txn-fixture");

        assertReadModelMatchesSummary(readModel, summary);
        assertThat(responseMapper.toResponse(readModel).status())
                .isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
    }

    @Test
    void availableMlScenarioPreservesExactIdentityAndMapsAvailable() {
        MlModelIdentity identity = new MlModelIdentity("python-logistic-fraud-model", "model-X", "feature-contract-v2");
        EngineIntelligenceSummary summary = EngineIntelligenceV2TestFixtures.rulesCriticalMlAvailableSummary(identity);
        EngineIntelligenceReadModel readModel = EngineIntelligenceV2TestFixtures
                .rulesCriticalMlAvailableReadModel("txn-fixture", identity);

        assertReadModelMatchesSummary(readModel, summary);
        assertThat(readModel.engines().get(1).modelIdentity()).isEqualTo(identity);
        assertThat(responseMapper.toResponse(readModel).status())
                .isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
    }

    @Test
    void rulesOnlyVersion2ReadModelIsUnavailableBecauseMandatoryMlIsMissing() {
        EngineIntelligenceReadModel readModel = EngineIntelligenceV2TestFixtures
                .negativeRulesOnlyV2ReadModel("txn-fixture");

        assertThat(readModel.contractVersion()).isEqualTo(EngineIntelligenceSummary.CONTRACT_VERSION);
        assertThat(readModel.engines()).extracting(engine -> engine.engineId()).containsExactly("rules.primary");
        assertThat(readModel.comparison()).isNotNull();
        assertThat(responseMapper.toResponse(readModel).status())
                .isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
    }

    private void assertReadModelMatchesSummary(
            EngineIntelligenceReadModel readModel,
            EngineIntelligenceSummary summary
    ) {
        assertThat(readModel.contractVersion()).isEqualTo(summary.contractVersion())
                .isEqualTo(EngineIntelligenceSummary.CONTRACT_VERSION);
        assertThat(readModel.generatedAt()).isEqualTo(summary.generatedAt());
        assertThat(readModel.comparison().comparisonType()).isEqualTo(summary.comparison().comparisonType());
        assertThat(readModel.comparison().comparedEngineIds())
                .containsExactlyElementsOf(summary.comparison().comparedEngineIds());
        assertThat(readModel.comparison().agreementStatus()).isEqualTo(summary.comparison().agreementStatus());
        assertThat(readModel.comparison().riskMismatchStatus()).isEqualTo(summary.comparison().riskMismatchStatus());
        assertThat(readModel.comparison().scoreDeltaBucket()).isEqualTo(summary.comparison().scoreDeltaBucket());
        assertThat(readModel.engines()).extracting(engine -> engine.engineId())
                .containsExactlyElementsOf(summary.engines().stream().map(engine -> engine.engineId()).toList());
        assertThat(readModel.engines()).extracting(engine -> engine.engineType())
                .containsExactlyElementsOf(summary.engines().stream().map(engine -> engine.engineType()).toList());
        assertThat(readModel.engines()).extracting(engine -> engine.status())
                .containsExactlyElementsOf(summary.engines().stream().map(engine -> engine.status()).toList());
        assertThat(readModel.engines()).extracting(engine -> engine.riskLevel())
                .containsExactlyElementsOf(summary.engines().stream().map(engine -> engine.riskLevel()).toList());
        assertThat(readModel.engines()).extracting(engine -> engine.scoreBucket())
                .containsExactlyElementsOf(summary.engines().stream().map(engine -> engine.scoreBucket()).toList());
        assertThat(readModel.engines()).extracting(engine -> engine.reasonCodes())
                .containsExactlyElementsOf(summary.engines().stream().map(engine -> engine.reasonCodes()).toList());
        assertThat(readModel.engines()).extracting(engine -> engine.modelIdentity())
                .containsExactlyElementsOf(summary.engines().stream().map(engine -> engine.modelIdentity()).toList());
    }
}
