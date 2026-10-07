package com.frauddetection.scoring.mapper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerEngineIntelligenceDisabledOutputContractTest {

    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
    private final TransactionScoredEventMapper mapper = new TransactionScoredEventMapper();

    @Test
    void disabledOutputUsesCanonicalEvidenceOutcome() throws Exception {
        assertDisabledOutputContract(serializedDefaultEvent());
    }

    @Test
    void defaultApplicationYamlKeepsDiagnosticsDisabledByDefault() throws Exception {
        assertThat(Files.readString(moduleRoot().resolve("src/main/resources/application.yml")))
                .contains("emit-enabled: ${FRAUD_SCORING_EVENTS_ENGINE_INTELLIGENCE_EMIT_ENABLED:false}");
        assertDisabledOutputContract(serializedDefaultEvent());
    }

    @Test
    void disabledOutputOmitsDiagnosticPayloads() throws Exception {
        assertThat(serializedDefaultEvent()).doesNotContain(
                "\"engineIntelligence\"",
                "\"engineIntelligence\":null",
                "\"mlPredictionEvidence\"",
                "diagnosticSignals",
                "agreementStatus",
                "riskMismatchStatus",
                "scoreDeltaBucket"
        );
    }

    private void assertDisabledOutputContract(String json) throws Exception {
        JsonNode tree = objectMapper.readTree(json);
        assertThat(tree.has("engineIntelligence")).isFalse();
        assertThat(tree.has("mlPredictionEvidence")).isFalse();
        assertThat(tree.path("mlPredictionEvidenceOmissionReason").textValue())
                .isEqualTo("DIAGNOSTIC_EMISSION_DISABLED");
        assertThat(tree.path("fraudScore").doubleValue()).isEqualTo(0.91d);
        assertThat(tree.path("riskLevel").textValue()).isEqualTo("CRITICAL");
        assertThat(tree.path("alertRecommended").booleanValue()).isTrue();
        assertThat(tree.path("reasonCodes")).hasSize(1);
    }

    private String serializedDefaultEvent() throws Exception {
        return objectMapper.writeValueAsString(mapper.toEvent(
                request(), scoreResult(), Optional.empty(),
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED, null
        ));
    }

    private FraudScoringRequest request() {
        return FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());
    }

    private FraudScoreResult scoreResult() {
        return new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.parse("2026-05-31T10:00:00Z"),
                List.of("HIGH_VELOCITY"),
                Map.of(),
                Map.of(),
                Map.of(),
                true
        );
    }

    private Path moduleRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("src/main")) ? current : current.resolve("fraud-scoring-service");
    }
}
