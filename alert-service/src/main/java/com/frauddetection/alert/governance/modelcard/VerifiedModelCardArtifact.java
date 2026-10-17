package com.frauddetection.alert.governance.modelcard;

import java.util.Objects;

public record VerifiedModelCardArtifact(ModelCard card, String manifestSha256) {

    public VerifiedModelCardArtifact {
        Objects.requireNonNull(card, "card is required");
        if (manifestSha256 == null || !manifestSha256.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("manifestSha256 must be lowercase SHA-256");
        }
    }
}
