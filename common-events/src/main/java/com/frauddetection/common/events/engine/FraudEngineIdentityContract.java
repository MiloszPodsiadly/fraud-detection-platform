package com.frauddetection.common.events.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FraudEngineIdentityContract {
    public static final String RULES_PRIMARY_ENGINE_ID = "rules.primary";
    public static final String PYTHON_ML_PRIMARY_ENGINE_ID = "ml.python.primary";
    public static final String VELOCITY_PRIMARY_ENGINE_ID = "velocity.primary";
    public static final int MAX_ENGINE_INTELLIGENCE_ENGINES = 3;

    private static final List<String> ENGINE_ORDER = List.of(
            RULES_PRIMARY_ENGINE_ID,
            PYTHON_ML_PRIMARY_ENGINE_ID,
            VELOCITY_PRIMARY_ENGINE_ID
    );
    private static final List<String> RULES_VS_ML_COMPARISON_ENGINE_IDS = List.of(
            RULES_PRIMARY_ENGINE_ID,
            PYTHON_ML_PRIMARY_ENGINE_ID
    );
    private static final Map<String, FraudEngineType> ENGINE_TYPES = Map.of(
            RULES_PRIMARY_ENGINE_ID, FraudEngineType.RULES,
            PYTHON_ML_PRIMARY_ENGINE_ID, FraudEngineType.ML_MODEL,
            VELOCITY_PRIMARY_ENGINE_ID, FraudEngineType.VELOCITY
    );

    private FraudEngineIdentityContract() {
    }

    public static List<String> engineOrder() {
        return ENGINE_ORDER;
    }

    public static List<String> rulesVsMlComparisonEngineIds() {
        return RULES_VS_ML_COMPARISON_ENGINE_IDS;
    }

    public static boolean isKnownEngineId(String engineId) {
        return ENGINE_TYPES.containsKey(engineId);
    }

    public static Optional<FraudEngineType> expectedTypeFor(String engineId) {
        return Optional.ofNullable(ENGINE_TYPES.get(engineId));
    }

    public static boolean hasExpectedType(String engineId, FraudEngineType engineType) {
        return expectedTypeFor(engineId)
                .map(expected -> expected == engineType)
                .orElse(false);
    }

    public static int orderOf(String engineId) {
        int index = ENGINE_ORDER.indexOf(engineId);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }
}
