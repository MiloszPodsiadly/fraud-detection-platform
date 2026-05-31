package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineExecutionPolicyTest {

    @Test
    void enginePolicyRequiresBoundedIdAndDeadline() {
        assertThatThrownBy(() -> new FraudEngineExecutionPolicy(null, Duration.ofMillis(1), true))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("engineId is required");
        assertThatThrownBy(() -> new FraudEngineExecutionPolicy("INVALID ID", Duration.ofMillis(1), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("engineId must be bounded");
        assertThatThrownBy(() -> new FraudEngineExecutionPolicy("rules.primary", null, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("deadline is required");
        assertThatThrownBy(() -> new FraudEngineExecutionPolicy("rules.primary", Duration.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deadline must be positive");
        assertThatThrownBy(() -> new FraudEngineExecutionPolicy("rules.primary", Duration.ofSeconds(6), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deadline exceeds bounded maximum");
    }

    @Test
    void orchestratorPolicyDefensivelyCopiesEntries() {
        List<FraudEngineExecutionPolicy> entries = new ArrayList<>(defaultEntries());
        FraudScoringOrchestratorExecutionPolicy policy = new FraudScoringOrchestratorExecutionPolicy(entries);

        entries.clear();

        assertThat(policy.enginePolicies()).hasSize(2);
        assertThatThrownBy(() -> policy.enginePolicies().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void orchestratorPolicyRejectsNullDuplicateAndMissingEntries() {
        assertThatThrownBy(() -> new FraudScoringOrchestratorExecutionPolicy(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("enginePolicies is required");
        assertThatThrownBy(() -> new FraudScoringOrchestratorExecutionPolicy(Arrays.asList(
                defaultEntries().get(0),
                null,
                defaultEntries().get(1)
        )))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("enginePolicies must not contain null entries");
        assertThatThrownBy(() -> new FraudScoringOrchestratorExecutionPolicy(List.of(
                defaultEntries().get(0),
                defaultEntries().get(0),
                defaultEntries().get(1)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_EXECUTION_POLICY_DUPLICATE_ENGINE_ID");
        assertThatThrownBy(() -> new FraudScoringOrchestratorExecutionPolicy(List.of(defaultEntries().get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_EXECUTION_POLICY_REQUIRED_ENTRY_MISSING");
    }

    @Test
    void lookupFailsFastWithoutExposingRequestedValue() {
        FraudScoringOrchestratorExecutionPolicy policy =
                FraudScoringOrchestratorExecutionPolicy.defaultInternalPolicy();

        assertThatThrownBy(() -> policy.policyFor("unknown.raw.value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_EXECUTION_POLICY_MISSING")
                .hasMessageNotContaining("unknown.raw.value");
    }

    @Test
    void defaultPolicyUsesExplicitRequiredMetadataAndLargerMlDeadline() {
        FraudScoringOrchestratorExecutionPolicy policy =
                FraudScoringOrchestratorExecutionPolicy.defaultInternalPolicy();

        FraudEngineExecutionPolicy rules = policy.policyFor(FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID);
        FraudEngineExecutionPolicy ml = policy.policyFor(FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID);

        assertThat(rules.required()).isTrue();
        assertThat(ml.required()).isFalse();
        assertThat(ml.deadline()).isGreaterThan(rules.deadline());
    }

    private List<FraudEngineExecutionPolicy> defaultEntries() {
        return FraudScoringOrchestratorExecutionPolicy.defaultInternalPolicy().enginePolicies();
    }
}
