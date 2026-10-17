package com.frauddetection.alert.governance.exactmodelshadowperformance;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import com.frauddetection.alert.governance.GovernanceTimestampContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record ExactModelShadowPerformanceSelector(
        String modelName,
        String modelVersion,
        String featureContractVersion,
        String modelArtifactSha256,
        String fromInclusive,
        String toInclusive
) {

    public ExactModelShadowPerformanceSelector {
        ExactModelArtifactIdentity identity = new ExactModelArtifactIdentity(
                modelName, modelVersion, featureContractVersion, modelArtifactSha256
        );
        modelName = identity.modelName();
        modelVersion = identity.modelVersion();
        featureContractVersion = identity.featureContractVersion();
        modelArtifactSha256 = identity.modelArtifactSha256();
        fromInclusive = timestamp(fromInclusive, "fromInclusive");
        toInclusive = timestamp(toInclusive, "toInclusive");
        if (fromInclusive != null && toInclusive != null
                && GovernanceTimestampContract.parse(fromInclusive, "fromInclusive")
                .isAfter(GovernanceTimestampContract.parse(toInclusive, "toInclusive"))) {
            throw new IllegalArgumentException("fromInclusive must not be after toInclusive");
        }
    }

    public String selectionKey() {
        String payload = "exact-model-shadow-selection-v1\n"
                + String.join("\n", modelName, modelVersion, featureContractVersion,
                modelArtifactSha256, nullToEmpty(fromInclusive), nullToEmpty(toInclusive));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String timestamp(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        GovernanceTimestampContract.parse(value, field);
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
