package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

final class ExactModelCardSelector {

    private final ExactModelArtifactIdentity identity;

    ExactModelCardSelector(ExactModelArtifactIdentity identity) {
        this.identity = Objects.requireNonNull(identity, "identity is required");
    }

    String selectionKey() {
        String payload = "exact-model-card-selection-v1\n" + String.join(
                "\n",
                identity.modelName(),
                identity.modelVersion(),
                identity.featureContractVersion(),
                identity.modelArtifactSha256()
        );
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
