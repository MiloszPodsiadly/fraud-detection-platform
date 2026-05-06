package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("production-readiness")
@Tag("integration")
class RegulatedMutationRollbackReadinessTest {

    @Test
    void disablingCheckpointRenewal_doesNotDisableFencing() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        RegulatedMutationFencedCommandWriter writer = new RegulatedMutationFencedCommandWriter(
                mongoTemplate,
                new AlertServiceMetrics(new SimpleMeterRegistry())
        );
        RegulatedMutationCommandDocument current = command(
                "command-stale-owner",
                "idem-stale-owner",
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
        current.setLeaseOwner("current-owner");
        current.setLeaseExpiresAt(Instant.now().plusSeconds(30));

        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        when(mongoTemplate.findById("command-stale-owner", RegulatedMutationCommandDocument.class))
                .thenReturn(current);

        assertThat(RegulatedMutationCheckpointRenewalService.disabled().isEnabledForTesting()).isFalse();
        assertThatThrownBy(() -> writer.transition(
                token("command-stale-owner", "stale-owner"),
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.BUSINESS_COMMITTING,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                update -> update.set("response_snapshot", "must-not-commit")
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .satisfies(exception -> assertThat(((StaleRegulatedMutationLeaseException) exception).reason())
                        .isEqualTo(StaleRegulatedMutationLeaseReason.STALE_LEASE_OWNER));
    }

    @Test
    void shrinkingRenewalBudget_doesNotCreateFalseSuccess() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        RegulatedMutationLeaseRenewalPolicy policy = new RegulatedMutationLeaseRenewalPolicy(
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                0
        );
        RegulatedMutationLeaseRenewalFailureHandler handler = new RegulatedMutationLeaseRenewalFailureHandler(
                mongoTemplate,
                policy,
                new RegulatedMutationPublicStatusMapper()
        );
        RegulatedMutationCommandDocument current = command(
                "command-budget",
                "idem-budget",
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
        current.setLeaseOwner("owner-budget");
        current.setLeaseExpiresAt(Instant.now().plusSeconds(30));

        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        handler.markBudgetExceededRecovery(token("command-budget", "owner-budget"), current, Instant.now());

        Document set = capturedSet(mongoTemplate);
        assertThat(set.get("execution_status")).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(set.get("degradation_reason")).isEqualTo("LEASE_RENEWAL_BUDGET_EXCEEDED");
        assertThat(set).doesNotContainKeys(
                "response_snapshot",
                "success_audit_id",
                "outbox_event_id",
                "decision_event_id"
        );
    }

    @Test
    void rollbackKeepsRecoveryCommandsVisible() {
        RegulatedMutationCommandRepository repository = mock(RegulatedMutationCommandRepository.class);
        RegulatedMutationCommandDocument recovery = command(
                "command-recovery",
                "idem-recovery",
                RegulatedMutationState.BUSINESS_COMMITTING,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED
        );
        RegulatedMutationCommandDocument finalizeRecovery = command(
                "command-finalize-recovery",
                "idem-finalize-recovery",
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED
        );

        when(repository.countByExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED)).thenReturn(2L);
        when(repository.countByExecutionStatus(RegulatedMutationExecutionStatus.FAILED)).thenReturn(0L);
        when(repository.countByExecutionStatusAndLeaseExpiresAtBefore(eq(RegulatedMutationExecutionStatus.PROCESSING), any()))
                .thenReturn(0L);
        when(repository.countByExecutionStatusAndAttemptCountGreaterThanEqual(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED, 3))
                .thenReturn(0L);
        when(repository.findTopByExecutionStatusOrderByUpdatedAtAsc(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED))
                .thenReturn(Optional.of(recovery));
        when(repository.findTop100ByExecutionStatusOrderByUpdatedAtAsc(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED))
                .thenReturn(List.of(recovery, finalizeRecovery));
        when(repository.findTop100ByExecutionStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(eq(RegulatedMutationExecutionStatus.PROCESSING), any()))
                .thenReturn(List.of());
        when(repository.findByIdempotencyKey("idem-finalize-recovery")).thenReturn(Optional.of(finalizeRecovery));

        RegulatedMutationRecoveryService service = new RegulatedMutationRecoveryService(
                repository,
                mock(RegulatedMutationAuditPhaseService.class),
                mock(com.frauddetection.alert.audit.AuditDegradationService.class),
                mock(AlertServiceMetrics.class),
                List.of(),
                Duration.ofMinutes(2)
        );

        RegulatedMutationRecoveryBacklogResponse backlog = service.backlog();
        RegulatedMutationCommandInspectionResponse inspection = service.inspect("idem-finalize-recovery");

        assertThat(backlog.totalRecoveryRequired()).isEqualTo(2L);
        assertThat(backlog.byState()).containsEntry("BUSINESS_COMMITTING", 1L)
                .containsEntry("FINALIZE_RECOVERY_REQUIRED", 1L);
        assertThat(inspection.state()).isEqualTo("FINALIZE_RECOVERY_REQUIRED");
        assertThat(inspection.executionStatus()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(inspection.idempotencyKeyMasked()).isNotEqualTo("idem-finalize-recovery");
    }

    @Test
    void rollbackDoesNotEnableFdp29Production() throws Exception {
        String application = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yml"));
        String legacyExecutor = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String evidenceExecutor = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(application).doesNotContain("evidence-gated-finalize.enabled: true");
        assertThat(application).doesNotContain("submit-decision.enabled: true");
        assertThat(legacyExecutor + evidenceExecutor)
                .doesNotContain("@Scheduled")
                .doesNotContain("fixedDelay")
                .doesNotContain("fixedRate");
    }

    @Test
    void rollbackApiSmoke_doesNotHideRecovery() throws Exception {
        RegulatedMutationRecoveryService service = mock(RegulatedMutationRecoveryService.class);
        when(service.inspect("idem-recovery")).thenReturn(new RegulatedMutationCommandInspectionResponse(
                RegulatedMutationIntentHasher.hash("idem-recovery"),
                "idem-r...very",
                "SUBMIT_ANALYST_DECISION",
                "ALERT",
                "alert-1",
                "BUSINESS_COMMITTING",
                "RECOVERY_REQUIRED",
                null,
                null,
                0,
                false,
                "attempted-audit",
                null,
                null,
                "RECOVERY_REQUIRED",
                "RECOVERY_REQUIRED",
                Instant.parse("2026-05-05T18:00:00Z")
        ));
        RegulatedMutationRecoveryController controller = new RegulatedMutationRecoveryController(
                service,
                new RegulatedMutationInspectionRateLimiter(30),
                mock(SensitiveReadAuditService.class)
        );

        MockMvcBuilders.standaloneSetup(controller)
                .defaultRequest(get("/").with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .build()
                .perform(get("/api/v1/regulated-mutations/idem-recovery")
                        .principal(new TestingAuthenticationToken("ops-admin", "n/a", "FRAUD_OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution_status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.state").value("BUSINESS_COMMITTING"))
                .andExpect(jsonPath("$.response_snapshot_present").value(false))
                .andExpect(jsonPath("$.resource_id").doesNotExist())
                .andExpect(jsonPath("$.resource_id_present").value(true))
                .andExpect(jsonPath("$.resource_id_hash").exists())
                .andExpect(jsonPath("$.last_error").doesNotExist())
                .andExpect(jsonPath("$.last_error_code").value("RECOVERY_REQUIRED"));
    }

    private RegulatedMutationCommandDocument command(
            String id,
            String idempotencyKey,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus status
    ) {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId(id);
        command.setIdempotencyKey(idempotencyKey);
        command.setIdempotencyKeyHash(RegulatedMutationIntentHasher.hash(idempotencyKey));
        command.setAction("SUBMIT_ANALYST_DECISION");
        command.setResourceType("ALERT");
        command.setResourceId("alert-1");
        command.setRequestHash("request-hash");
        command.setIntentHash("intent-hash");
        command.setState(state);
        command.setExecutionStatus(status);
        command.setMutationModelVersion(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        command.setUpdatedAt(Instant.now().minusSeconds(120));
        return command;
    }

    private RegulatedMutationClaimToken token(String commandId, String owner) {
        Instant now = Instant.now();
        return new RegulatedMutationClaimToken(
                commandId,
                owner,
                now.plusSeconds(30),
                now,
                1,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
    }

    @SuppressWarnings("unchecked")
    private Document capturedSet(MongoTemplate mongoTemplate) {
        org.mockito.ArgumentCaptor<Update> captor = org.mockito.ArgumentCaptor.forClass(Update.class);
        org.mockito.Mockito.verify(mongoTemplate).updateFirst(
                any(Query.class),
                captor.capture(),
                eq(RegulatedMutationCommandDocument.class)
        );
        return (Document) captor.getValue().getUpdateObject().get("$set");
    }
}
