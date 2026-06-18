package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.EngineIntelligenceEngineStatusResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceComparisonReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceDiagnosticSignalReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceEngineReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceWarningReadModel;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceResponseMapperTest {

    private final EngineIntelligenceResponseMapper mapper = new EngineIntelligenceResponseMapper();
    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void mapsMissingEngineIntelligenceToAbsentResponse() {
        EngineIntelligenceResponse response = mapper.toResponse(EngineIntelligenceReadModel.notProjected("txn-old"));

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.ABSENT);
        assertThat(response.contractVersion()).isNull();
        assertThat(response.generatedAt()).isNull();
        assertThat(response.comparison()).isNull();
        assertThat(response.engines()).isEmpty();
        assertThat(response.diagnosticSignals()).isEmpty();
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void mapsAvailableRulesAndMlIntelligenceWithoutChangingComparison() {
        EngineIntelligenceResponse response = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.DISAGREEMENT
        ));

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.AVAILABLE);
        assertThat(response.contractVersion()).isEqualTo(1);
        assertThat(response.comparison().agreementStatus()).isEqualTo(EngineIntelligenceAgreementStatus.DISAGREEMENT);
        assertThat(response.engines()).extracting("engineId").containsExactly("rules.primary", "ml.python.primary");
        assertThat(response.engines()).extracting("status")
                .containsExactly(EngineIntelligenceEngineStatusResponse.AVAILABLE, EngineIntelligenceEngineStatusResponse.AVAILABLE);
        assertThat(response.diagnosticSignals()).hasSize(1);
        assertThat(response.warnings()).hasSize(1);
    }

    @Test
    void mapsUnavailableTimeoutAndSkippedEngineStatesVisibly() {
        EngineIntelligenceResponse unavailable = mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.UNAVAILABLE,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA
        ));
        EngineIntelligenceResponse timeout = mapper.toResponse(readModel(
                FraudEngineStatus.TIMEOUT,
                FraudEngineStatus.SKIPPED,
                EngineIntelligenceAgreementStatus.INSUFFICIENT_DATA
        ));

        assertThat(unavailable.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(unavailable.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.UNAVAILABLE);
        assertThat(timeout.status()).isEqualTo(EngineIntelligenceResponseStatus.DEGRADED);
        assertThat(timeout.engines()).extracting("status")
                .contains(EngineIntelligenceEngineStatusResponse.TIMEOUT, EngineIntelligenceEngineStatusResponse.NOT_APPLICABLE);
    }

    @Test
    void unavailableResponseIsExplicitAndEmpty() {
        EngineIntelligenceResponse response = mapper.unavailable();

        assertThat(response.status()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
        assertThat(response.engines()).isEmpty();
        assertThat(response.diagnosticSignals()).isEmpty();
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void publicResponseDoesNotExposeRawInternalPayloads() throws Exception {
        String serialized = objectMapper.writeValueAsString(mapper.toResponse(readModel(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceAgreementStatus.PARTIAL
        )));

        assertThat(serialized).doesNotContain(
                "FraudEngineResult",
                "rawFeatureVector",
                "rawMlRequest",
                "rawMlResponse",
                "rawEvidence",
                "rawPayload",
                "groundTruth",
                "trainingLabel",
                "finalDecision",
                "stackTrace",
                "exceptionMessage",
                "modelPath",
                "secret",
                "token"
        );
    }

    private EngineIntelligenceReadModel readModel(
            FraudEngineStatus rulesStatus,
            FraudEngineStatus mlStatus,
            EngineIntelligenceAgreementStatus agreementStatus
    ) {
        return EngineIntelligenceReadModel.projected(
                "txn-1",
                1,
                Instant.parse("2026-06-18T10:00:00Z"),
                new EngineIntelligenceComparisonReadModel(
                        agreementStatus,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(
                        engine("rules.primary", FraudEngineType.RULES, rulesStatus, List.of("RULE_MATCH")),
                        engine("ml.python.primary", FraudEngineType.ML_MODEL, mlStatus, List.of("ML_SIGNAL"))
                ),
                List.of(new EngineIntelligenceDiagnosticSignalReadModel(
                        "rules.primary",
                        FraudEngineType.RULES,
                        rulesStatus,
                        EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                        RiskLevel.HIGH,
                        EngineIntelligenceScoreBucket.HIGH,
                        "RULE_MATCH"
                )),
                List.of(new EngineIntelligenceWarningReadModel(EngineIntelligenceWarningCode.ENGINE_RESULT_LIMIT_APPLIED, 1))
        );
    }

    private EngineIntelligenceEngineReadModel engine(
            String engineId,
            FraudEngineType engineType,
            FraudEngineStatus status,
            List<String> reasonCodes
    ) {
        return new EngineIntelligenceEngineReadModel(
                engineId,
                engineType,
                status,
                status == FraudEngineStatus.AVAILABLE ? RiskLevel.HIGH : null,
                status == FraudEngineStatus.AVAILABLE ? EngineIntelligenceScoreBucket.HIGH : EngineIntelligenceScoreBucket.UNAVAILABLE,
                reasonCodes
        );
    }
}
