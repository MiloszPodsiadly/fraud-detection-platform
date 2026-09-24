package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlModelIdentityContractTest {

    @Test
    void acceptsCanonicalModelIdentitySyntax() {
        MlModelIdentity identity = new MlModelIdentity(
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                "2026-05-30.feature-contract.v1"
        );

        assertThat(identity.modelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(identity.modelVersion()).isEqualTo("2026-05-30.v1");
        assertThat(identity.featureContractVersion()).isEqualTo("2026-05-30.feature-contract.v1");
    }

    @Test
    void rejectsUnsafeModelIdentitySyntax() {
        assertThatThrownBy(() -> new MlModelIdentity(
                "python/logistic-fraud-model",
                "2026-05-30.v1",
                "2026-05-30.feature-contract.v1"
        )).hasMessageContaining("modelName");
        assertThatThrownBy(() -> new MlModelIdentity(
                "python-logistic-fraud-model",
                "2026-05-30:v1",
                "2026-05-30.feature-contract.v1"
        )).hasMessageContaining("modelVersion");
        assertThatThrownBy(() -> new MlModelIdentity(
                "python-logistic-fraud-model",
                "2026-05-30.v1",
                "token-contract-v1"
        )).hasMessageContaining("featureContractVersion");
    }
}
