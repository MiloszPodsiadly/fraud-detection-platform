package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceContractTest {
    private static final String DEVICE_REASON = "DEVICE_SCORE_NOT_CALIBRATED";

    @Test
    void v2AcceptsDeviceAfterExistingCanonicalEnginesWithoutChangingComparisonIdentity() throws Exception {
        var summary = v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                device(List.of(DEVICE_REASON))));

        assertThat(summary.contractVersion()).isEqualTo(2);
        assertThat(summary.engines()).extracting(EngineIntelligenceEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary", "device.primary");
        assertThat(summary.comparison().comparedEngineIds())
                .containsExactly("rules.primary", "ml.python.primary");
        String json = EngineIntelligenceTestSupport.objectMapper().writeValueAsString(summary);
        var decoded = EngineIntelligenceTestSupport.objectMapper().readValue(json, EngineIntelligenceSummary.class);
        assertThat(decoded).isEqualTo(summary);
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_SERIALIZED_BYTES);
    }

    @Test
    void v2AllowsFourOrderedEnginesAndCoherentOperationalDeviceSignal() {
        var device = device(List.of(DEVICE_REASON));
        var diagnostic = new EngineIntelligenceDiagnosticSignal(
                "device.primary", FraudEngineType.DEVICE_RISK, FraudEngineStatus.DEGRADED,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL, null,
                EngineIntelligenceScoreBucket.UNAVAILABLE, DEVICE_REASON);
        var summary = v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                velocity(), device), List.of(diagnostic));

        assertThat(summary.contractVersion()).isEqualTo(2);
        assertThat(summary.engines()).hasSize(4);
        assertThat(summary.diagnosticSignals()).containsExactly(diagnostic);
    }

    @Test
    void v2CarriesAllFiveFixedDeviceSignalDiagnosticsWithoutRiskBuckets() {
        List<String> reasons = List.of(
                "DEVICE_NOVELTY_NOT_SEEN_IN_WINDOW",
                "DEVICE_SWITCH_OBSERVED",
                "DEVICE_SHARED_DEVICE_UNSUPPORTED_SOURCE",
                "DEVICE_COUNTRY_MISMATCH_UNSUPPORTED_SOURCE",
                "DEVICE_PROXY_OR_VPN_UNSUPPORTED_SOURCE");
        var signals = reasons.stream().map(reason -> new EngineIntelligenceDiagnosticSignal(
                "device.primary", FraudEngineType.DEVICE_RISK, FraudEngineStatus.DEGRADED,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL, null,
                EngineIntelligenceScoreBucket.UNAVAILABLE, reason)).toList();
        var summary = v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                device(reasons)), signals);

        assertThat(summary.engines().getLast().reasonCodes()).containsExactlyElementsOf(reasons);
        assertThat(summary.diagnosticSignals()).containsExactlyElementsOf(signals);
        assertThat(summary.engines().getLast().riskLevel()).isNull();
        assertThat(summary.engines().getLast().scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void v2KeepsDeviceAndVelocityOptional() {
        var twoEngines = v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH)));
        var velocityOnly = v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                velocity()));
        var deviceOnly = v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                device(List.of(DEVICE_REASON))));

        assertThat(twoEngines.engines()).hasSize(2);
        assertThat(twoEngines.contractVersion()).isEqualTo(2);
        assertThat(velocityOnly.contractVersion()).isEqualTo(2);
        assertThat(deviceOnly.contractVersion()).isEqualTo(2);
        assertThat(velocityOnly.engines()).extracting(EngineIntelligenceEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary");
        assertThat(deviceOnly.engines()).extracting(EngineIntelligenceEngineResult::engineId)
                .containsExactly("rules.primary", "ml.python.primary", "device.primary");
    }

    @Test
    void currentReadersRejectV1AndWrongEngineReasonCodes() {
        assertThatThrownBy(() -> new EngineIntelligenceSummary(1,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(EngineIntelligenceTestSupport.rulesEngine(),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                        device(List.of(DEVICE_REASON))),
                EngineIntelligenceTestSupport.comparison(), List.of(), List.of()))
                .hasMessage("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");

        assertThatThrownBy(() -> new EngineIntelligenceSummary(1,
                EngineIntelligenceTestSupport.GENERATED_AT,
                List.of(EngineIntelligenceTestSupport.rulesEngine(List.of(DEVICE_REASON)),
                        EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH)),
                EngineIntelligenceTestSupport.comparison(), List.of(), List.of()))
                .hasMessage("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
    }

    @Test
    void currentNestedReaderRejectsV1Summaries() throws Exception {
        String json = "{\"contractVersion\":1,\"generatedAt\":\"2026-06-18T10:00:00Z\","
                + "\"engines\":[],\"comparison\":{\"comparisonType\":\"RULES_VS_ML\","
                + "\"comparedEngineIds\":[\"rules.primary\",\"ml.python.primary\"],"
                + "\"agreementStatus\":\"PARTIAL\",\"riskMismatchStatus\":\"NOT_COMPARABLE\","
                + "\"scoreDeltaBucket\":\"UNAVAILABLE\"},\"diagnosticSignals\":[],\"warnings\":[]}";

        assertThatThrownBy(() -> EngineIntelligenceTestSupport.objectMapper()
                .readValue(json, EngineIntelligenceSummary.class))
                .hasMessageContaining("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
    }

    @Test
    void currentProducerAndIdentityUseOneFourEngineContract() {
        assertThat(EngineIntelligenceSummary.CONTRACT_VERSION).isEqualTo(2);
        assertThat(FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_ENGINES).isEqualTo(4);
        assertThat(FraudEngineIdentityContract.engineOrder())
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary", "device.primary");
    }

    @Test
    void v2RejectsUnknownVersionInvalidOrderDeviceScoreAndReasonMisuse() {
        assertThatThrownBy(() -> v2(3, List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH))))
                .hasMessage("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
        assertThatThrownBy(() -> v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(), device(List.of(DEVICE_REASON)),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH))))
                .hasMessage("ENGINE_INTELLIGENCE_ENGINE_ORDER_INVALID");
        assertThatThrownBy(() -> v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                velocity(), device(List.of(DEVICE_REASON)), device(List.of(DEVICE_REASON)))))
                .hasMessage("ENGINE_INTELLIGENCE_ENGINES_LIMIT_EXCEEDED");
        assertThatThrownBy(() -> new EngineIntelligenceEngineResult("device.primary", FraudEngineType.VELOCITY,
                FraudEngineStatus.UNAVAILABLE, null, EngineIntelligenceScoreBucket.UNAVAILABLE, List.of()))
                .hasMessage("ENGINE_INTELLIGENCE_ENGINE_TYPE_MISMATCH");
        assertThatThrownBy(() -> v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                device(List.of("HIGH_VELOCITY")))))
                .hasMessage("ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED_FOR_ENGINE");
        assertThatThrownBy(() -> v2(List.of(
                EngineIntelligenceTestSupport.rulesEngine(),
                EngineIntelligenceTestSupport.mlEngine(RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH),
                new EngineIntelligenceEngineResult("device.primary", FraudEngineType.DEVICE_RISK,
                        FraudEngineStatus.AVAILABLE, RiskLevel.LOW,
                        EngineIntelligenceScoreBucket.LOW, List.of("DEVICE_NOVELTY")))))
                .hasMessage("ENGINE_INTELLIGENCE_DEVICE_SCORE_UNCALIBRATED");
    }

    @Test
    void byteBoundaryIsExplicitAndFailClosed() {
        FraudEngineIdentityContract.requireEngineIntelligenceSerializedSize(1);
        FraudEngineIdentityContract.requireEngineIntelligenceSerializedSize(
                FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_SERIALIZED_BYTES);
        for (int invalid : new int[]{0, FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_SERIALIZED_BYTES + 1}) {
            assertThatThrownBy(() -> FraudEngineIdentityContract.requireEngineIntelligenceSerializedSize(invalid))
                    .hasMessage("ENGINE_INTELLIGENCE_PAYLOAD_BYTES_INVALID");
        }
    }

    private static EngineIntelligenceSummary v2(List<EngineIntelligenceEngineResult> engines) {
        return v2(2, engines);
    }

    private static EngineIntelligenceSummary v2(List<EngineIntelligenceEngineResult> engines,
                                                List<EngineIntelligenceDiagnosticSignal> signals) {
        return new EngineIntelligenceSummary(2, EngineIntelligenceTestSupport.GENERATED_AT,
                engines, EngineIntelligenceTestSupport.comparison(), signals, List.of());
    }

    private static EngineIntelligenceSummary v2(int version, List<EngineIntelligenceEngineResult> engines) {
        return new EngineIntelligenceSummary(version, EngineIntelligenceTestSupport.GENERATED_AT,
                engines, EngineIntelligenceTestSupport.comparison(), List.of(), List.of());
    }

    private static EngineIntelligenceEngineResult device(List<String> reasons) {
        return new EngineIntelligenceEngineResult("device.primary", FraudEngineType.DEVICE_RISK,
                FraudEngineStatus.DEGRADED, null, EngineIntelligenceScoreBucket.UNAVAILABLE, reasons);
    }

    private static EngineIntelligenceEngineResult velocity() {
        return new EngineIntelligenceEngineResult("velocity.primary", FraudEngineType.VELOCITY,
                FraudEngineStatus.UNAVAILABLE, null, EngineIntelligenceScoreBucket.UNAVAILABLE,
                List.of("VELOCITY_FEATURES_UNAVAILABLE"));
    }
}
