package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record FraudScoringOrchestratorExecutionPolicy(
        List<FraudEngineExecutionPolicy> enginePolicies
) {
    private static final Set<String> ALLOWED_ENGINE_IDS = Set.of(
            FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID,
            FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID
    );

    public FraudScoringOrchestratorExecutionPolicy {
        Objects.requireNonNull(enginePolicies, "enginePolicies is required");
        Set<String> engineIds = new HashSet<>();
        for (FraudEngineExecutionPolicy policy : enginePolicies) {
            Objects.requireNonNull(policy, "enginePolicies must not contain null entries");
            if (!ALLOWED_ENGINE_IDS.contains(policy.engineId())) {
                throw new IllegalArgumentException("ENGINE_EXECUTION_POLICY_UNKNOWN_ENGINE_ID");
            }
            if (!engineIds.add(policy.engineId())) {
                throw new IllegalArgumentException("ENGINE_EXECUTION_POLICY_DUPLICATE_ENGINE_ID");
            }
        }
        requirePolicy(engineIds, FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID);
        requirePolicy(engineIds, FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID);
        enginePolicies = List.copyOf(enginePolicies);
    }

    public static FraudScoringOrchestratorExecutionPolicy defaultInternalPolicy() {
        return new FraudScoringOrchestratorExecutionPolicy(List.of(
                new FraudEngineExecutionPolicy(
                        FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID,
                        Duration.ofMillis(250),
                        true
                ),
                new FraudEngineExecutionPolicy(
                        FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID,
                        Duration.ofSeconds(1),
                        false
                )
        ));
    }

    public FraudEngineExecutionPolicy policyFor(String engineId) {
        Objects.requireNonNull(engineId, "engineId is required");
        return enginePolicies.stream()
                .filter(policy -> policy.engineId().equals(engineId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ENGINE_EXECUTION_POLICY_MISSING"));
    }

    private static void requirePolicy(Set<String> engineIds, String engineId) {
        if (!engineIds.contains(engineId)) {
            throw new IllegalArgumentException("ENGINE_EXECUTION_POLICY_REQUIRED_ENTRY_MISSING");
        }
    }
}
