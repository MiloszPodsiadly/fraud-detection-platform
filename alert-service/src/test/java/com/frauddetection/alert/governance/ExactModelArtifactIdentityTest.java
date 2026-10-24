package com.frauddetection.alert.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactModelArtifactIdentityTest {

    @Test
    void artifactDigestIsPartOfCanonicalIdentity() {
        ExactModelArtifactIdentity first = identity("a".repeat(64));
        ExactModelArtifactIdentity second = identity("b".repeat(64));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsInvalidFeatureContractAndArtifactDigest() {
        assertThatThrownBy(() -> new ExactModelArtifactIdentity(
                "fraud-model", "2026.10", "feature contract", "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("featureContractVersion");
        assertThatThrownBy(() -> identity("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelArtifactSha256");
    }

    private ExactModelArtifactIdentity identity(String sha256) {
        return new ExactModelArtifactIdentity("fraud-model", "2026.10", "feature-contract-v2", sha256);
    }
}
