package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.service.RuleBasedFraudScoringEngine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RulesModelVersionConsistencyTest {

    @Test
    void preservedRulesV1BehaviorKeepsModelAndDescriptorVersionsStable() {
        var productionEngine = new RuleBasedFraudScoringEngine(
                new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED)
        );
        var adapter = new RuleBasedSignalEngine(new FeatureSnapshotReaderFactory(), productionEngine);

        var productionResult = productionEngine.score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));
        var descriptor = adapter.descriptor();

        assertThat(productionResult.modelName()).isEqualTo("rule-based-engine");
        assertThat(productionResult.modelVersion()).isEqualTo("v1");
        assertThat(descriptor.engineType()).isEqualTo(FraudEngineType.RULES);
        assertThat(descriptor.version()).isEqualTo("1.0.0");
    }
}
