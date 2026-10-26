package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;

import java.util.List;

/** Public summary identity by version; the legacy engine registry remains a three-engine runtime. */
public final class EngineIntelligenceVersionedIdentity {
    public static final int V1 = 1;
    public static final int V2 = 2;
    public static final String DEVICE_PRIMARY_ENGINE_ID = "device.primary";
    public static final int MAX_V2_ENGINES = 4;
    public static final int MAX_SERIALIZED_BYTES = 16_384;
    private static final List<String> V2_ORDER = List.of(
            FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID,
            FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
            FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
            DEVICE_PRIMARY_ENGINE_ID);

    private EngineIntelligenceVersionedIdentity() {
    }

    public static boolean supportedVersion(int version) {
        return version == V1 || version == V2;
    }

    public static List<String> engineOrder(int version) {
        return switch (version) {
            case V1 -> FraudEngineIdentityContract.engineOrder();
            case V2 -> V2_ORDER;
            default -> throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
        };
    }

    public static int maxEngines(int version) {
        return engineOrder(version).size();
    }

    /** Enforce at byte-oriented ingress or egress before parsing or publishing. */
    public static void requireSerializedSize(int bytes) {
        if (bytes < 1 || bytes > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_PAYLOAD_BYTES_INVALID");
        }
    }

    public static boolean hasExpectedType(String engineId, FraudEngineType engineType) {
        return FraudEngineIdentityContract.hasExpectedType(engineId, engineType)
                || DEVICE_PRIMARY_ENGINE_ID.equals(engineId) && engineType == FraudEngineType.DEVICE_RISK;
    }
}
