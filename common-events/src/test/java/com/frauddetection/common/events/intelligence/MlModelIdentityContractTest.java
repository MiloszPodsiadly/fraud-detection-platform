package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlModelIdentityContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void sharedGoldenCasesMatchRuntimeIdentityPolicy() throws Exception {
        JsonNode fixture = objectMapper.readTree(repositoryRoot()
                .resolve("contract-fixtures/public-api/ml-model-identity-cases.json")
                .toFile());
        JsonNode canonical = fixture.get("canonicalIdentity");
        boolean syntaxRuntimeDifferenceCovered = false;

        for (JsonNode identityCase : fixture.get("cases")) {
            String field = identityCase.get("field").asString();
            String value = identityCase.get("value").asString();
            boolean validRuntime = identityCase.get("validRuntime").booleanValue();
            String modelName = "modelName".equals(field) ? value : canonical.get("modelName").asString();
            String modelVersion = "modelVersion".equals(field) ? value : canonical.get("modelVersion").asString();
            String featureContractVersion = "featureContractVersion".equals(field)
                    ? value
                    : canonical.get("featureContractVersion").asString();

            boolean accepted;
            try {
                new MlModelIdentity(modelName, modelVersion, featureContractVersion);
                accepted = true;
            } catch (IllegalArgumentException exception) {
                accepted = false;
            }
            assertThat(accepted)
                    .as(identityCase.get("caseId").asString())
                    .isEqualTo(validRuntime);
            syntaxRuntimeDifferenceCovered |= identityCase.get("validSyntax").booleanValue() && !validRuntime;
        }

        assertThat(syntaxRuntimeDifferenceCovered).isTrue();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        return current.endsWith("common-events") ? current.getParent() : current;
    }
}
