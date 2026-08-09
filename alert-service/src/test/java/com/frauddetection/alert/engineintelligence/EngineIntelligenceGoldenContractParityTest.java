package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModelMapper;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceGoldenContractParityTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final EngineIntelligenceProjectionMapper projectionMapper = new EngineIntelligenceProjectionMapper(
            new EngineIntelligenceProjectionPolicy()
    );
    private final EngineIntelligenceReadModelMapper readModelMapper = new EngineIntelligenceReadModelMapper();
    private final EngineIntelligenceResponseMapper responseMapper = new EngineIntelligenceResponseMapper();

    @Test
    void twoEngineGoldenFixtureProjectsToReadModelAndPublicResponseWithoutVelocity() throws Exception {
        EngineIntelligenceResponse response = publicResponse("engine_intelligence_two_engine_golden.json");

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(response.contractVersion()).isEqualTo(EngineIntelligenceSummary.CONTRACT_VERSION);
        assertThat(response.engines()).extracting(engine -> engine.engineId())
                .containsExactly(
                        FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID
                );
        assertThat(response.engines()).extracting(engine -> engine.engineType())
                .containsExactly(FraudEngineType.RULES, FraudEngineType.ML_MODEL);
        assertThat(response.diagnosticSignals()).isEmpty();
    }

    @Test
    void threeEngineGoldenFixtureProjectsToReadModelAndPublicResponseInCanonicalOrder() throws Exception {
        EngineIntelligenceResponse response = publicResponse("engine_intelligence_three_engine_golden.json");
        JsonNode registry = jsonMapper.readTree(fixturePath("engine_registry_contract.json").toFile());
        List<String> registryOrder = StreamSupport.stream(registry.get("order").spliterator(), false)
                .map(JsonNode::textValue)
                .toList();

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(response.contractVersion()).isEqualTo(EngineIntelligenceSummary.CONTRACT_VERSION);
        assertThat(response.engines()).extracting(engine -> engine.engineId())
                .containsExactlyElementsOf(registryOrder);
        assertThat(response.engines()).extracting(engine -> engine.engineType())
                .containsExactly(FraudEngineType.RULES, FraudEngineType.ML_MODEL, FraudEngineType.VELOCITY);
        assertThat(response.comparison().comparisonType().name()).isEqualTo("RULES_VS_ML");
        assertThat(response.comparison().comparedEngineIds())
                .containsExactlyElementsOf(FraudEngineIdentityContract.rulesVsMlComparisonEngineIds());
        assertThat(response.diagnosticSignals()).extracting(signal -> signal.engineId())
                .containsExactly(
                        FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                        FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                        FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
                        FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID
                );
    }

    @Test
    void sharedInvalidSemanticCasesMarkedForCommonContractDoNotReachProjection() throws Exception {
        JsonNode cases = jsonMapper.readTree(fixturePath("invalid_semantic_cases.json").toFile()).get("cases");

        for (JsonNode semanticCase : StreamSupport.stream(cases.spliterator(), false).toList()) {
            if (!"engine-intelligence".equals(semanticCase.get("category").textValue())
                    || !semanticCase.get("commonReject").booleanValue()) {
                continue;
            }

            assertThatThrownBy(() -> jsonMapper.readValue(
                    jsonMapper.writeValueAsString(semanticCase.get("engineIntelligence")),
                    EngineIntelligenceSummary.class
            ))
                    .as(semanticCase.get("caseId").textValue())
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private EngineIntelligenceResponse publicResponse(String fixtureName) throws Exception {
        EngineIntelligenceSummary summary = jsonMapper.readValue(
                Files.readString(fixturePath(fixtureName)),
                EngineIntelligenceSummary.class
        );
        EngineIntelligenceProjection projection = projectionMapper.map("txn-golden", summary, null)
                .projection()
                .orElseThrow();
        EngineIntelligenceReadModel readModel = readModelMapper.map(projection);

        assertThat(readModel.engines()).extracting(engine -> engine.engineId())
                .containsExactlyElementsOf(expectedEngineOrder(summary));
        assertThat(readModel.contractVersion()).isEqualTo(EngineIntelligenceSummary.CONTRACT_VERSION);

        return responseMapper.toResponse(readModel);
    }

    private List<String> expectedEngineOrder(EngineIntelligenceSummary summary) {
        return summary.engines().stream()
                .map(engine -> engine.engineId())
                .toList();
    }

    private Path fixturePath(String fixtureName) {
        Path root = Path.of(System.getProperty("project.root", ".")).toAbsolutePath().normalize();
        Path fromRoot = root.resolve(Path.of(
                "common-events",
                "src",
                "test",
                "resources",
                "fixtures",
                "engine-intelligence",
                fixtureName
        ));
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        return root.getParent().resolve(Path.of(
                "common-events",
                "src",
                "test",
                "resources",
                "fixtures",
                "engine-intelligence",
                fixtureName
        ));
    }
}
