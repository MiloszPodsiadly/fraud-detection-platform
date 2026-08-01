package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceGoldenFixtureTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void goldenTwoEngineFixtureRemainsValidForVelocityDisabledRuntime() throws Exception {
        EngineIntelligenceSummary summary = mapper.readValue(
                Files.readString(fixturePath("engine_intelligence_two_engine_golden.json")),
                EngineIntelligenceSummary.class
        );

        assertThat(summary.contractVersion()).isEqualTo(EngineIntelligenceSummary.CONTRACT_VERSION);
        assertThat(summary.engines()).extracting(EngineIntelligenceEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary");
    }

    @Test
    void goldenThreeEngineFixtureDeserializesThroughCommonContract() throws Exception {
        EngineIntelligenceSummary summary = mapper.readValue(
                Files.readString(fixturePath("engine_intelligence_three_engine_golden.json")),
                EngineIntelligenceSummary.class
        );

        assertThat(summary.engines()).hasSize(3);
        assertThat(summary.engines()).extracting(EngineIntelligenceEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary");
        assertThat(summary.engines()).extracting(EngineIntelligenceEngineResult::engineType)
                .containsExactly(FraudEngineType.RULES, FraudEngineType.ML_MODEL, FraudEngineType.VELOCITY);
        assertThat(summary.warnings()).isEmpty();
    }

    @Test
    void canonicalRegistryFixtureMatchesCommonEngineIdentityContract() throws Exception {
        JsonNode registry = mapper.readTree(fixturePath("engine_registry_contract.json").toFile());

        assertThat(registry.get("maxEngineCount").intValue())
                .isEqualTo(FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_ENGINES);
        assertThat(StreamSupport.stream(registry.get("order").spliterator(), false)
                .map(JsonNode::textValue)
                .toList())
                .containsExactlyElementsOf(FraudEngineIdentityContract.engineOrder());
        assertThat(StreamSupport.stream(registry.get("comparison").get("comparedEngineIds").spliterator(), false)
                .map(JsonNode::textValue)
                .toList())
                .containsExactlyElementsOf(FraudEngineIdentityContract.rulesVsMlComparisonEngineIds());
        assertThat(registry.get("comparison").get("comparisonType").textValue()).isEqualTo("RULES_VS_ML");

        assertThat(StreamSupport.stream(registry.get("engines").spliterator(), false)
                .map(engine -> engine.get("engineType").textValue())
                .toList())
                .containsExactly(
                        FraudEngineType.RULES.name(),
                        FraudEngineType.ML_MODEL.name(),
                        FraudEngineType.VELOCITY.name()
                );
    }

    @Test
    void sharedInvalidSemanticCasesMarkedForCommonContractAreRejected() throws Exception {
        JsonNode cases = mapper.readTree(fixturePath("invalid_semantic_cases.json").toFile()).get("cases");

        for (JsonNode semanticCase : cases) {
            if (!"engine-intelligence".equals(semanticCase.get("category").textValue())
                    || !semanticCase.get("commonReject").booleanValue()) {
                continue;
            }

            assertThatThrownBy(() -> mapper.readValue(
                    mapper.writeValueAsString(semanticCase.get("engineIntelligence")),
                    EngineIntelligenceSummary.class
            ))
                    .as(semanticCase.get("caseId").textValue())
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid/engine_intelligence_duplicate_velocity.json",
            "invalid/engine_intelligence_duplicate_rules.json",
            "invalid/engine_intelligence_duplicate_ml.json",
            "invalid/engine_intelligence_duplicate_rules_missing_ml.json",
            "invalid/engine_intelligence_too_many_engines.json",
            "invalid/engine_intelligence_velocity_type_mismatch.json",
            "invalid/engine_intelligence_invalid_order.json",
            "invalid/engine_intelligence_unknown_future_engine.json"
    })
    void invalidGoldenFixturesAreRejectedByCommonContract(String fixtureName) {
        Path fixture = Path.of("src/test/resources/fixtures/engine-intelligence", fixtureName);

        assertThatThrownBy(() -> mapper.readValue(Files.readString(fixture), EngineIntelligenceSummary.class))
                .isInstanceOf(RuntimeException.class);
    }

    private Path fixturePath(String fixtureName) {
        return Path.of("src/test/resources/fixtures/engine-intelligence", fixtureName);
    }
}
