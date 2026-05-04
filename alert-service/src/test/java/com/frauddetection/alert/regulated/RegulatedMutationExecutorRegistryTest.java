package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationExecutorRegistryTest {

    @Test
    void legacyModelResolvesLegacyExecutor() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationExecutor evidence = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy, evidence), true);

        assertThat(registry.executorFor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION)).isSameAs(legacy);
    }

    @Test
    void nullModelVersionResolvesLegacyExecutor() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy), false);

        assertThat(registry.executorFor((RegulatedMutationModelVersion) null)).isSameAs(legacy);
    }

    @Test
    void evidenceGatedModelResolvesEvidenceGatedExecutor() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationExecutor evidence = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy, evidence), true);

        assertThat(registry.executorFor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1)).isSameAs(evidence);
    }

    @Test
    void duplicateExecutorRegistrationFailsStartup() {
        RegulatedMutationExecutor first = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationExecutor second = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);

        assertThatThrownBy(() -> new RegulatedMutationExecutorRegistry(List.of(first, second), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate regulated mutation executor");
    }

    @Test
    void missingLegacyExecutorFailsStartup() {
        RegulatedMutationExecutor evidence = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);

        assertThatThrownBy(() -> new RegulatedMutationExecutorRegistry(List.of(evidence), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGACY_REGULATED_MUTATION");
    }

    @Test
    void evidenceGatedEnabledWithoutEvidenceExecutorFailsStartup() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);

        assertThatThrownBy(() -> new RegulatedMutationExecutorRegistry(List.of(legacy), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVIDENCE_GATED_FINALIZE_V1");
    }

    @Test
    void unsupportedModelVersionFailsClosed() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy), false);

        assertThatThrownBy(() -> registry.executorFor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVIDENCE_GATED_FINALIZE_V1");
    }

    @Test
    void evidenceGatedDisabledAllowsLegacyOnlyRegistry() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);

        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy), false);

        assertThat(registry.executorFor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION)).isSameAs(legacy);
    }

    @Test
    void evidenceGatedEnabledAllowsCompleteRegistry() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationExecutor evidence = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);

        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy, evidence), true);

        assertThat(registry.executorFor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION)).isSameAs(legacy);
        assertThat(registry.executorFor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1)).isSameAs(evidence);
    }

    private RegulatedMutationExecutor executor(RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationExecutor executor = mock(RegulatedMutationExecutor.class);
        when(executor.modelVersion()).thenReturn(modelVersion);
        return executor;
    }
}
