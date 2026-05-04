package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoRegulatedMutationCoordinatorRoutingTest {

    @Test
    void newCommandWithNullModelVersionRoutesToLegacyExecutor() {
        RegulatedMutationExecutor legacy = executor(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                "legacy"
        );
        Fixture fixture = new Fixture(legacy);
        fixture.noExistingCommand();
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.coordinator.commit(command(
                null,
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                businessWrites
        ));

        assertThat(result.response()).isEqualTo("legacy");
        assertThat(businessWrites).hasValue(0);
        verify(legacy).execute(any(), eq("idem-1"), any());
        assertThat(fixture.saved.getMutationModelVersion()).isNull();
        assertThat(fixture.saved.mutationModelVersionOrLegacy())
                .isEqualTo(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
    }

    @Test
    void existingCommandWithNullModelVersionRoutesToLegacyExecutor() {
        RegulatedMutationExecutor legacy = executor(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                "legacy"
        );
        Fixture fixture = new Fixture(legacy);
        fixture.existingCommand(commandDocument(null, AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE));
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.coordinator.commit(command(
                null,
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                businessWrites
        ));

        assertThat(result.response()).isEqualTo("legacy");
        assertThat(businessWrites).hasValue(0);
        verify(legacy).execute(any(), eq("idem-1"), any());
    }

    @Test
    void evidenceGatedModelRoutesToEvidenceGatedExecutor() {
        RegulatedMutationExecutor legacy = executor(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                "legacy"
        );
        RegulatedMutationExecutor evidence = executor(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                "evidence"
        );
        Fixture fixture = new Fixture(legacy, evidence);
        fixture.noExistingCommand();
        AtomicInteger businessWrites = new AtomicInteger();

        RegulatedMutationResult<String> result = fixture.coordinator.commit(command(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                businessWrites
        ));

        assertThat(result.response()).isEqualTo("evidence");
        assertThat(businessWrites).hasValue(0);
        verify(evidence).execute(any(), eq("idem-1"), any());
        verify(legacy, never()).execute(any(), anyString(), any());
    }

    @Test
    void unsupportedEvidenceGatedModelDoesNotDowngradeToLegacyExecutor() {
        RegulatedMutationExecutor legacy = executor(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                "legacy"
        );
        Fixture fixture = new Fixture(legacy);
        fixture.existingCommand(commandDocument(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        ));
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.coordinator.commit(command(
                        RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                        AuditAction.SUBMIT_ANALYST_DECISION,
                        AuditResourceType.ALERT,
                        businessWrites
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVIDENCE_GATED_FINALIZE_V1");

        assertThat(businessWrites).hasValue(0);
        verify(legacy, never()).execute(any(), anyString(), any());
    }

    @Test
    void unsupportedActionResourcePairFailsBeforeExecutorExecution() {
        RegulatedMutationExecutor legacy = executor(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                "legacy"
        );
        RegulatedMutationExecutor evidence = executor(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                "evidence"
        );
        when(evidence.supports(AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE)).thenReturn(false);
        Fixture fixture = new Fixture(legacy, evidence);
        fixture.existingCommand(commandDocument(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE
        ));
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.coordinator.commit(command(
                        RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                        AuditAction.UPDATE_FRAUD_CASE,
                        AuditResourceType.FRAUD_CASE,
                        businessWrites
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support action/resource")
                .hasMessageContaining("UPDATE_FRAUD_CASE/FRAUD_CASE");

        assertThat(businessWrites).hasValue(0);
        verify(evidence, never()).execute(any(), anyString(), any());
        verify(legacy, never()).execute(any(), anyString(), any());
    }

    private RegulatedMutationCommand<String, String> command(
            RegulatedMutationModelVersion modelVersion,
            AuditAction action,
            AuditResourceType resourceType,
            AtomicInteger businessWrites
    ) {
        return new RegulatedMutationCommand<>(
                "idem-1",
                "principal-7",
                "resource-1",
                resourceType,
                action,
                "corr-1",
                "request-hash-1",
                context -> {
                    businessWrites.incrementAndGet();
                    return "business-result";
                },
                (result, state) -> state.name(),
                response -> null,
                snapshot -> "restored",
                state -> state.name(),
                null,
                modelVersion
        );
    }

    private static RegulatedMutationCommandDocument commandDocument(
            RegulatedMutationModelVersion modelVersion,
            AuditAction action,
            AuditResourceType resourceType
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("mutation-1");
        document.setIdempotencyKey("idem-1");
        document.setActorId("principal-7");
        document.setResourceId("resource-1");
        document.setResourceType(resourceType.name());
        document.setAction(action.name());
        document.setCorrelationId("corr-1");
        document.setRequestHash("request-hash-1");
        document.setIntentHash("request-hash-1");
        document.setIntentResourceId("resource-1");
        document.setIntentAction(action.name());
        document.setIntentActorId("principal-7");
        document.setMutationModelVersion(modelVersion);
        document.setState(RegulatedMutationState.REQUESTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        document.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return document;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RegulatedMutationExecutor executor(RegulatedMutationModelVersion modelVersion, String response) {
        RegulatedMutationExecutor executor = mock(RegulatedMutationExecutor.class);
        when(executor.modelVersion()).thenReturn(modelVersion);
        when(executor.supports(any(), any())).thenReturn(true);
        when(executor.execute(any(RegulatedMutationCommand.class), anyString(), any(RegulatedMutationCommandDocument.class)))
                .thenReturn(new RegulatedMutationResult<>(RegulatedMutationState.REQUESTED, response));
        return executor;
    }

    private static final class Fixture {
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final MongoRegulatedMutationCoordinator coordinator;
        private RegulatedMutationCommandDocument saved;

        private Fixture(RegulatedMutationExecutor... executors) {
            this.coordinator = new MongoRegulatedMutationCoordinator(
                    commandRepository,
                    new RegulatedMutationExecutorRegistry(List.of(executors), false)
            );
            when(commandRepository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> {
                saved = invocation.getArgument(0);
                return saved;
            });
        }

        private void noExistingCommand() {
            when(commandRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        }

        private void existingCommand(RegulatedMutationCommandDocument document) {
            saved = document;
            when(commandRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(document));
        }
    }
}
