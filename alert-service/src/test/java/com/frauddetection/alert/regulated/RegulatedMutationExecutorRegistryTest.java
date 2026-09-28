package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationExecutorRegistryTest {

    @Test
    void currentModelResolvesCanonicalExecutor() {
        RegulatedMutationExecutor current = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(current));

        assertThat(registry.executorFor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1)).isSameAs(current);
    }

    @Test
    void nullModelFailsClosed() {
        RegulatedMutationExecutor current = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(current));

        assertThatThrownBy(() -> registry.executorFor((RegulatedMutationModelVersion) null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported persisted");
    }

    @Test
    void nullModelExecutorRegistrationFailsClosed() {
        RegulatedMutationExecutor invalid = executor(null);

        assertThatThrownBy(() -> new RegulatedMutationExecutorRegistry(List.of(invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null model version");
    }

    @Test
    void duplicateExecutorRegistrationFailsStartup() {
        RegulatedMutationExecutor first = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        RegulatedMutationExecutor second = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);

        assertThatThrownBy(() -> new RegulatedMutationExecutorRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate regulated mutation executor");
    }

    @Test
    void documentRoutingRejectsMissingOrUnknownContractFields() {
        RegulatedMutationExecutor current = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(current));
        RegulatedMutationCommandDocument missingAction = document(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        missingAction.setAction(null);
        RegulatedMutationCommandDocument unknownResource = document(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );
        unknownResource.setResourceType("UNBOUNDED_RESOURCE");

        assertThatThrownBy(() -> registry.executorFor(missingAction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("action is required");
        assertThatThrownBy(() -> registry.executorFor(unknownResource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported regulated mutation command resource type");
    }

    @Test
    void documentRoutingRejectsUnsupportedActionResourcePair() {
        RegulatedMutationExecutor current = executor(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        RegulatedMutationExecutorRegistry registry = new RegulatedMutationExecutorRegistry(List.of(current));

        assertThatThrownBy(() -> registry.executorFor(document(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.FRAUD_CASE
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported regulated mutation action/resource");
    }

    @Test
    void canonicalExecutorSupportsExactlyTheRegisteredOperations() {
        EvidenceGatedFinalizeExecutor current = evidenceGatedExecutor();

        RegulatedMutationDefinitions.all().forEach(definition ->
                assertThat(current.supports(definition.action(), definition.resourceType())).isTrue());
        assertThat(current.supports(AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.FRAUD_CASE)).isFalse();
        assertThat(current.supports(AuditAction.READ_AUDIT_EVENTS, AuditResourceType.AUDIT_EVENT)).isFalse();
        assertThat(current.supports(null, AuditResourceType.ALERT)).isFalse();
    }

    private RegulatedMutationExecutor executor(RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationExecutor executor = mock(RegulatedMutationExecutor.class);
        when(executor.modelVersion()).thenReturn(modelVersion);
        when(executor.supports(any(), any())).thenReturn(true);
        return executor;
    }

    private RegulatedMutationCommandDocument document(AuditAction action, AuditResourceType resourceType) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setAction(action.name());
        document.setResourceType(resourceType.name());
        return document;
    }

    private EvidenceGatedFinalizeExecutor evidenceGatedExecutor() {
        return new EvidenceGatedFinalizeExecutor(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Duration.ofSeconds(30)
        );
    }
}
