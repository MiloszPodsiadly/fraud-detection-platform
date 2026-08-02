package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.api.EngineIntelligenceComparisonResponse;
import com.frauddetection.alert.api.EngineIntelligenceDiagnosticSignalResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceContractDriftGuardTest {
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void publicEngineIdentityRegistryHasRulesMlRequiredAndVelocityOptional() throws Exception {
        Map<String, Object> registry = readJson("common-events/src/test/resources/fixtures/engine-intelligence/engine_registry_contract.json");

        assertThat(registry.get("maxEngineCount")).isEqualTo(FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_ENGINES);
        assertThat(registry.get("order")).isEqualTo(FraudEngineIdentityContract.engineOrder());
        assertThat(((Map<?, ?>) registry.get("comparison")).get("comparedEngineIds"))
                .isEqualTo(FraudEngineIdentityContract.rulesVsMlComparisonEngineIds());
        assertThat((List<Map<String, Object>>) registry.get("engines"))
                .extracting(engine -> engine.get("requiredForComparison"))
                .containsExactly(true, true, false);
    }

    @Test
    void publicAndGovernanceTimestampMatricesStayUnified() throws Exception {
        Map<String, Object> publicApi = readJson("contract-fixtures/public-api/canonical-utc-timestamp-cases.json");
        Map<String, Object> governance = readJson("contract-fixtures/governance/canonical-utc-timestamp-cases.json");

        List<Map<String, Object>> publicCases = (List<Map<String, Object>>) publicApi.get("cases");
        List<Object> governanceValid = (List<Object>) governance.get("valid");
        List<Object> governanceInvalid = (List<Object>) governance.get("invalid");

        for (Map<String, Object> timestampCase : publicCases) {
            Object value = timestampCase.get("value");
            if (Boolean.TRUE.equals(timestampCase.get("valid"))) {
                assertThat(governanceValid).contains(value);
            } else {
                assertThat(governanceInvalid).contains(value);
            }
        }
        assertThat(publicApi.get("pattern").toString()).contains("fraction-1-to-9");
        assertThat(governanceValid).contains("2026-06-11T10:15:30.123456789Z");
        assertThat(governanceInvalid).contains("2026-06-11T10:15:30.1234567890Z");
    }

    @Test
    void publicEventProjectionAndApiDoNotAddDedicatedVelocityScoreFields() throws Exception {
        List<Class<?>> publicTypes = List.of(
                TransactionScoredEvent.class,
                EngineIntelligenceSummary.class,
                EngineIntelligenceEngineResult.class,
                EngineIntelligenceComparison.class,
                EngineIntelligenceDiagnosticSignal.class,
                EngineIntelligenceProjection.class,
                EngineIntelligenceEngineProjection.class,
                EngineIntelligenceDiagnosticSignalProjection.class,
                EngineIntelligenceResponse.class,
                EngineIntelligenceEngineResponse.class,
                EngineIntelligenceComparisonResponse.class,
                EngineIntelligenceDiagnosticSignalResponse.class
        );

        assertThat(publicTypes.stream()
                .flatMap(type -> fieldNames(type).stream()))
                .doesNotContain("velocityScore", "velocityProbability", "velocityConfidence");
        assertThat(sources(
                "docs/openapi/alert_service.openapi.yaml",
                "analyst-console-ui/src/engineIntelligence/engineIntelligenceContractValidation.js",
                "analyst-console-ui/src/transactions/transactionRiskIntelligenceValidation.js"
        )).doesNotContain("velocityScore", "velocityProbability", "velocityConfidence");
    }

    @Test
    void openApiAndFixtureKeepVelocityOutOfRulesVsMlComparison() throws Exception {
        Map<String, Object> fixture = readJson("contract-fixtures/public-api/engine-intelligence-full-path-composition-response.json");
        Map<String, Object> comparison = (Map<String, Object>) fixture.get("comparison");
        String openApi = sources("docs/openapi/alert_service.openapi.yaml");

        assertThat(comparison.get("comparisonType")).isEqualTo("RULES_VS_ML");
        assertThat(comparison.get("comparedEngineIds")).isEqualTo(List.of("rules.primary", "ml.python.primary"));
        assertThat(comparison.toString()).doesNotContain("velocity.primary");
        assertThat(openApi)
                .contains("Velocity is not included in score delta semantics")
                .contains("Rules and ML")
                .contains("optional Velocity");
    }

    @Test
    void contractVersionOneDecisionDocumentsRepositoryControlledAtomicMigration() throws Exception {
        String adr = sources("docs/architecture/engine_intelligence_contract_version_adr.md");
        String consumerReadiness = sources("docs/architecture/engine_intelligence_consumer_readiness.md");

        assertThat(adr)
                .contains("contractVersion=1")
                .contains("repository-controlled")
                .contains("atomically")
                .contains("does not prove the absence")
                .contains("external consumers");
        assertThat(consumerReadiness)
                .contains("no direct `TransactionScoredEvent` deserializer in API or analyst console UI");
    }

    private Map<String, Object> readJson(String relativePath) throws IOException {
        return objectMapper.readValue(repositoryRoot().resolve(relativePath).toFile(), new TypeReference<>() {
        });
    }

    private String sources(String... relativePaths) throws IOException {
        StringBuilder sources = new StringBuilder();
        for (String relativePath : relativePaths) {
            sources.append(Files.readString(repositoryRoot().resolve(relativePath))).append('\n');
        }
        return sources.toString();
    }

    private List<String> fieldNames(Class<?> type) {
        if (type.isRecord()) {
            return Stream.of(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
        }
        return Stream.of(type.getDeclaredFields())
                .map(Field::getName)
                .toList();
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("alert-service"))
                    && Files.isDirectory(candidate.resolve("common-events"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("REPOSITORY_ROOT_MISSING");
    }
}
