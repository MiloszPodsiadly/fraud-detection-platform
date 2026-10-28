package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceReasonCodeOwnershipFixtureTest {

    @Test
    void checkedInCrossLanguageOwnershipFixtureMatchesTheJavaContractSource() throws Exception {
        Map<?, ?> fixture;
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/engine-intelligence/engine_reason_code_ownership_contract.json"
        )) {
            fixture = JsonMapper.builder().build().readValue(input, Map.class);
        }

        Set<String> device = fixtureCodes(fixture, "deviceOwnedReasonCodes");
        Set<String> orchestrator = fixtureCodes(fixture, "sharedOrchestratorReasonCodes");
        Set<String> shared = fixtureCodes(fixture, "nonDeviceSharedReasonCodes");
        Set<String> rulesMlShared = fixtureCodes(fixture, "rulesMlSharedReasonCodes");
        Set<String> mlVelocityShared = fixtureCodes(fixture, "mlVelocitySharedReasonCodes");
        Set<String> rules = fixtureCodes(fixture, "rulesOwnedReasonCodes");
        Set<String> ml = fixtureCodes(fixture, "mlOwnedReasonCodes");
        Set<String> velocity = fixtureCodes(fixture, "velocityOwnedReasonCodes");

        assertThat(device).containsExactlyInAnyOrderElementsOf(EngineIntelligenceReasonCodePolicy.deviceReasonCodes());
        assertThat(orchestrator).containsExactlyInAnyOrderElementsOf(
                EngineIntelligenceReasonCodePolicy.sharedOrchestratorReasonCodes()
        );
        assertThat(EngineIntelligenceReasonCodePolicy.reasonCodesForEngine(
                FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID
        )).containsExactlyInAnyOrderElementsOf(merge(shared, rulesMlShared, rules, orchestrator));
        assertThat(EngineIntelligenceReasonCodePolicy.reasonCodesForEngine(
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID
        )).containsExactlyInAnyOrderElementsOf(merge(shared, rulesMlShared, mlVelocityShared, ml, orchestrator));
        assertThat(EngineIntelligenceReasonCodePolicy.reasonCodesForEngine(
                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID
        )).containsExactlyInAnyOrderElementsOf(merge(shared, mlVelocityShared, velocity, orchestrator));
        assertThat(EngineIntelligenceReasonCodePolicy.reasonCodesForEngine(
                FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID
        )).containsExactlyInAnyOrderElementsOf(merge(device, orchestrator));

        assertThat(EngineIntelligenceReasonCodePolicy.isAllowedForEngine(
                FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID, "MODEL_HIGH_RISK"
        )).isFalse();
        assertThat(EngineIntelligenceReasonCodePolicy.isAllowedForEngine(
                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID, "DEVICE_NOVELTY"
        )).isFalse();
        assertThat(EngineIntelligenceReasonCodePolicy.isAllowedForEngine(
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, "DEVICE_NOVELTY"
        )).isTrue();
        assertThat(EngineIntelligenceReasonCodePolicy.isAllowedForEngine(
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, "ORCHESTRATOR_ENGINE_PUBLICATION_FAILURE"
        )).isTrue();
    }

    private Set<String> fixtureCodes(Map<?, ?> fixture, String field) {
        Object value = fixture.get(field);
        assertThat(value).isInstanceOf(List.class);
        List<?> codes = (List<?>) value;
        assertThat(codes).allSatisfy(code -> assertThat(code).isInstanceOf(String.class));
        List<String> actual = codes.stream().map(String.class::cast).toList();
        assertThat(actual).doesNotHaveDuplicates();
        return Set.copyOf(actual);
    }

    @SafeVarargs
    private Set<String> merge(Set<String>... groups) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (Set<String> group : groups) {
            merged.addAll(group);
        }
        return Set.copyOf(merged);
    }
}
