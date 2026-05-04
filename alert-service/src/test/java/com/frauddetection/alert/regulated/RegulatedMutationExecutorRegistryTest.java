package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void documentRoutingValidatesExecutorActionAndResourceSupport() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        when(legacy.supports(AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT)).thenReturn(false);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy), false);

        assertThatThrownBy(() -> registry.executorFor(document(
                        RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                        AuditAction.SUBMIT_ANALYST_DECISION,
                        AuditResourceType.ALERT
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support action/resource")
                .hasMessageContaining("SUBMIT_ANALYST_DECISION/ALERT");
    }

    @Test
    void documentRoutingRejectsMissingActionBeforeExecutorUse() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy), false);
        RegulatedMutationCommandDocument document = document(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        document.setAction(null);

        assertThatThrownBy(() -> registry.executorFor(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("action is required");
    }

    @Test
    void documentRoutingRejectsUnknownResourceTypeBeforeExecutorUse() {
        RegulatedMutationExecutor legacy = executor(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(legacy), false);
        RegulatedMutationCommandDocument document = document(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        document.setResourceType("UNBOUNDED_RESOURCE");

        assertThatThrownBy(() -> registry.executorFor(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported regulated mutation command resource type");
    }

    private RegulatedMutationExecutor executor(RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationExecutor executor = mock(RegulatedMutationExecutor.class);
        when(executor.modelVersion()).thenReturn(modelVersion);
        when(executor.supports(any(), any())).thenReturn(true);
        return executor;
    }

    private RegulatedMutationCommandDocument document(
            RegulatedMutationModelVersion modelVersion,
            AuditAction action,
            AuditResourceType resourceType
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setMutationModelVersion(modelVersion);
        document.setAction(action.name());
        document.setResourceType(resourceType.name());
        return document;
    }
}
