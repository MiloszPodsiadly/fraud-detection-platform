package com.frauddetection.alert.regulated;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RegulatedMutationArchitectureTest {

    @Test
    void alertManagementServiceMustNotOrchestrateRegulatedDecisionMutationDirectly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/AlertManagementService.java"
        ));

        assertThat(source).doesNotContain("AuditMutationRecorder");
        assertThat(source).doesNotContain("auditService.audit");
        assertThat(source).doesNotContain("saveDecisionWithOutbox");
        assertThat(source).contains("submitDecisionRegulatedMutationService.submit");
    }

    @Test
    void requestPathMustNotExposeFullyAnchoredDecisionStatus() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/SubmitDecisionRegulatedMutationService.java"
        ));

        assertThat(source).doesNotContain("COMMITTED_FULLY_ANCHORED");
    }

    @Test
    void coordinatorMustNotDependOnSubmitDecisionResponseType() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"
        ));

        assertThat(source).doesNotContain("SubmitAnalystDecisionResponse");
    }

    @Test
    void submitDecisionResponseMapperMustRemainPure() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/SubmitDecisionRegulatedMutationService.java"
        ));
        int mapperStart = source.indexOf("private SubmitAnalystDecisionResponse response(");
        int mapperEnd = source.indexOf("private SubmitAnalystDecisionResponse statusResponse(");

        assertThat(mapperStart).isGreaterThanOrEqualTo(0);
        assertThat(mapperEnd).isGreaterThan(mapperStart);
        String mapperSource = source.substring(mapperStart, mapperEnd);
        assertThat(mapperSource).doesNotContain("alertRepository.save");
        assertThat(mapperSource).doesNotContain("decisionOutboxWriter");
    }

    @Test
    void regulatedSubmitDecisionServiceMustNotWriteAuditDirectly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/SubmitDecisionRegulatedMutationService.java"
        ));

        assertThat(source).doesNotContain("auditService.audit");
        assertThat(source).doesNotContain("AuditMutationRecorder");
    }

    @Test
    void regulatedSubmitDecisionServiceMustNotWriteRepositoryDirectly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/SubmitDecisionRegulatedMutationService.java"
        ));

        assertThat(source).doesNotContain("alertRepository.save");
        assertThat(source).contains("mutationHandler.applyDecision");
    }

    @Test
    void submitDecisionMutationHandlerIsTheAllowedDomainWriteAdapter() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/mutation/submitdecision/SubmitDecisionMutationHandler.java"
        ));

        assertThat(source).contains("alertRepository.save");
        assertThat(source).doesNotContain("auditService.audit");
        assertThat(source).doesNotContain("AuditMutationRecorder");
    }

    @Test
    void servicePackageMustNotContainSubmitDecisionMutationHandler() {
        assertThat(Files.exists(Path.of(
                "src/main/java/com/frauddetection/alert/service/SubmitDecisionMutationHandler.java"
        ))).isFalse();
    }

    @Test
    void decisionOutboxReconciliationServiceMustNotWriteRepositoryDirectly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/DecisionOutboxReconciliationService.java"
        ));

        assertThat(source).doesNotContain("alertRepository.save");
        assertThat(source).contains("mutationHandler.applyResolution");
    }

    @Test
    void decisionOutboxMutationHandlerIsTheAllowedDomainWriteAdapter() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/mutation/decisionoutbox/DecisionOutboxReconciliationMutationHandler.java"
        ));

        assertThat(source).contains("alertRepository.save");
        assertThat(source).doesNotContain("auditService.audit");
        assertThat(source).doesNotContain("AuditMutationRecorder");
    }

    @Test
    void requestPathMustNotPublishBrokerEventsDirectly() throws Exception {
        String serviceSource = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/SubmitDecisionRegulatedMutationService.java"
        ));
        String coordinatorSource = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"
        ));
        String legacyExecutorSource = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String evidenceExecutorSource = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String handlerSource = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/mutation/submitdecision/SubmitDecisionMutationHandler.java"
        ));

        assertThat(serviceSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(coordinatorSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(legacyExecutorSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(evidenceExecutorSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(handlerSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(serviceSource).doesNotContain(".publish(");
        assertThat(coordinatorSource).doesNotContain(".publish(");
        assertThat(legacyExecutorSource).doesNotContain(".publish(");
        assertThat(evidenceExecutorSource).doesNotContain(".publish(");
        assertThat(handlerSource).doesNotContain(".publish(");
    }

    @Test
    void transactionalOutboxPublisherIsTheOnlyBrokerPublishingBoundary() throws Exception {
        String scheduledWrapper = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/FraudDecisionOutboxPublisher.java"
        ));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/outbox/OutboxPublisherCoordinator.java"
        ));

        assertThat(scheduledWrapper).contains("OutboxPublisherCoordinator");
        assertThat(scheduledWrapper).doesNotContain("publisher.publish");
        assertThat(coordinator).contains("FraudDecisionEventPublisher");
        assertThat(coordinator).contains("publisher.publish(record.getPayload())");
    }

    @Test
    void kafkaTemplateMustRemainOutsideBusinessRequestPath() throws Exception {
        List<Path> javaFiles;
        try (java.util.stream.Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            javaFiles = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String normalized = path.toString().replace('\\', '/');
                        return !normalized.endsWith("messaging/FraudDecisionEventPublisher.java")
                                && !normalized.endsWith("config/KafkaConfig.java")
                                && !normalized.endsWith("config/AlertKafkaConfig.java")
                                && !normalized.contains("/messaging/");
                    })
                    .toList();
        }

        for (Path path : javaFiles) {
            assertThat(Files.readString(path))
                    .as("KafkaTemplate leak in " + path)
                    .doesNotContain("KafkaTemplate");
        }
    }

    @Test
    void transactionalOutboxRecordMustRemainAuthoritativeForDeliveryDecisions() throws Exception {
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/outbox/OutboxPublisherCoordinator.java"
        ));
        String recovery = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/outbox/OutboxRecoveryService.java"
        ));

        assertThat(coordinator).doesNotContain("countByDecisionOutboxStatus");
        assertThat(coordinator).doesNotContain("findTopByDecisionOutboxStatus");
        assertThat(recovery).contains("TransactionalOutboxRecordRepository");
        assertThat(recovery).doesNotContain("countByDecisionOutboxStatus");
    }

    @Test
    void manualOutboxResolutionMustUseRegulatedMutationCoordinator() throws Exception {
        String recovery = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/outbox/OutboxRecoveryService.java"
        ));

        assertThat(recovery).contains("RegulatedMutationCoordinator");
        assertThat(recovery).contains("regulatedMutationCoordinator.commit(command)");
        assertThat(recovery).doesNotContain("auditService.audit");
        assertThat(recovery).doesNotContain("AuditOutcome.SUCCESS");
    }

    @Test
    void regulatedMutationHandlersMustNotWritePhaseAudits() throws Exception {
        List<Path> handlers;
        try (java.util.stream.Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert/regulated/mutation"))) {
            handlers = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        for (Path handler : handlers) {
            String source = Files.readString(handler);
            assertThat(source).as("regulated mutation handler must not inject AuditService: " + handler)
                    .doesNotContain("AuditService");
            assertThat(source).as("regulated mutation handler must not inject AuditEventRepository: " + handler)
                    .doesNotContain("AuditEventRepository");
            assertThat(source).as("regulated mutation handler must not inject PersistentAuditEventPublisher: " + handler)
                    .doesNotContain("PersistentAuditEventPublisher");
            assertThat(source).as("regulated mutation handler must not write phase audit directly: " + handler)
                    .doesNotContain("auditService.audit");
            assertThat(source).as("regulated mutation handler must not write SUCCESS audit directly: " + handler)
                    .doesNotContain("AuditOutcome.SUCCESS");
            assertThat(source).as("regulated mutation handler must not publish Kafka directly: " + handler)
                    .doesNotContain("FraudDecisionEventPublisher")
                    .doesNotContain("KafkaTemplate")
                    .doesNotContain(".publish(");
            assertThat(source).as("regulated mutation handler must not publish external anchors directly: " + handler)
                    .doesNotContain("ExternalAuditAnchor")
                    .doesNotContain("ExternalAuditIntegrity")
                    .doesNotContain("ExternalAuditPublication");
        }
    }

    @Test
    void fdp29FinalizeTransactionMustUseLocalAuditWriterOnly() throws Exception {
        String executor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String finalizeMethod = executor.substring(
                executor.indexOf("private <R, S> RegulatedMutationResult<S> finalizeVisibleMutation("),
                executor.indexOf("private <R, S> RegulatedMutationResult<S> markRecoveryRequired(")
        );

        assertThat(finalizeMethod).contains("localSuccessAudit(command, document)");
        assertThat(finalizeMethod).doesNotContain("auditPhaseService.recordPhase");
        assertThat(finalizeMethod).doesNotContain("AuditService");
        assertThat(finalizeMethod).doesNotContain("AuditEventPublisher");
        assertThat(finalizeMethod).doesNotContain("ExternalAuditAnchorPublisher");
        assertThat(finalizeMethod).doesNotContain("FraudDecisionEventPublisher");
        assertThat(finalizeMethod).doesNotContain("KafkaTemplate");
        assertThat(finalizeMethod).doesNotContain("SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL");
    }

    @Test
    void coordinatorMustRouteThroughExecutorRegistry() throws Exception {
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"
        ));

        assertThat(coordinator).contains("RegulatedMutationExecutorRegistry");
        assertThat(coordinator).contains("executorRegistry.executorFor(document).execute(command, idempotencyKey, document)");
        assertThat(coordinator).doesNotContain("private <R, S> void prepareEvidence(");
        assertThat(coordinator).doesNotContain("private <R, S> RegulatedMutationResult<S> finalizeVisibleMutation(");
        assertThat(coordinator).doesNotContain("command.mutation().execute");
        assertThat(coordinator).doesNotContain("transactionRunner.runLocalCommit");
        assertThat(coordinator).doesNotContain("RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter");
        assertThat(coordinator).doesNotContain("auditPhaseService.recordPhase");
        assertThat(coordinator).doesNotContain("AuditService");
        assertThat(coordinator).doesNotContain("AuditEventPublisher");
        assertThat(coordinator).doesNotContain("FraudDecisionEventPublisher");
        assertThat(coordinator).doesNotContain("OutboxPublisher");
        assertThat(coordinator).doesNotContain("KafkaTemplate");
        assertThat(coordinator).doesNotContain("localSuccessAudit(");
    }

    @Test
    void productionCoordinatorConstructorMustDependOnExecutorRegistry() throws Exception {
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"
        ));

        assertThat(coordinator).contains("@Autowired");
        assertThat(coordinator).contains("MongoRegulatedMutationCoordinator(");
        assertThat(coordinator).contains("RegulatedMutationCommandRepository commandRepository");
        assertThat(coordinator).contains("RegulatedMutationExecutorRegistry executorRegistry");
        assertThat(coordinator).contains("Production wiring path. Registry is Spring-managed and startup-validated.");
        assertThat(coordinator).contains("Compatibility constructor for unit tests");
        assertThat(coordinator).contains("must not replace registry bean validation");
    }

    @Test
    void executorRegistryMustValidateActionResourceSupportForDocumentRouting() throws Exception {
        String registry = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationExecutorRegistry.java"
        ));
        String executorInterface = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationExecutor.java"
        ));
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(executorInterface).contains("boolean supports(AuditAction action, AuditResourceType resourceType)");
        assertThat(registry).contains("executor.supports(action, resourceType)");
        assertThat(registry).contains("does not support action/resource");
        assertThat(evidenceExecutor).contains("action == AuditAction.SUBMIT_ANALYST_DECISION");
        assertThat(evidenceExecutor).contains("resourceType == AuditResourceType.ALERT");
    }

    @Test
    void legacyExecutorMustNotDependOnEvidenceGatedOrExternalFinalizeBoundaries() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));

        assertThat(legacyExecutor).doesNotContain("RegulatedMutationLocalAuditPhaseWriter");
        assertThat(legacyExecutor).doesNotContain("EvidencePreconditionEvaluator");
        assertThat(legacyExecutor).doesNotContain("EvidenceGatedFinalizeStateMachine");
        assertThat(legacyExecutor).doesNotContain("FraudDecisionEventPublisher");
        assertThat(legacyExecutor).doesNotContain("KafkaTemplate");
        assertThat(legacyExecutor).doesNotContain("ExternalAuditAnchorPublisher");
        assertThat(legacyExecutor).doesNotContain("ExternalAuditIntegrity");
        assertThat(legacyExecutor).doesNotContain("ExternalAuditPublication");
    }

    @Test
    void evidenceGatedOnlyBoundariesMustRemainFdp29Only() throws Exception {
        List<Path> javaFiles;
        try (java.util.stream.Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            javaFiles = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        for (Path path : javaFiles) {
            String normalized = path.toString().replace('\\', '/');
            String source = Files.readString(path);
            if (normalized.endsWith("regulated/EvidenceGatedFinalizeExecutor.java")
                    || normalized.endsWith("regulated/EvidenceGatedFinalizeStartupGuard.java")
                    || normalized.endsWith("regulated/EvidencePreconditionEvaluator.java")
                    || normalized.endsWith("regulated/EvidenceGatedFinalizeStateMachine.java")
                    || normalized.endsWith("audit/RegulatedMutationLocalAuditPhaseWriter.java")) {
                continue;
            }
            assertThat(source)
                    .as("Evidence-gated helper leaked outside FDP-29 executor/startup boundary: " + path)
                    .doesNotContain("RegulatedMutationLocalAuditPhaseWriter")
                    .doesNotContain("EvidencePreconditionEvaluator")
                    .doesNotContain("EvidenceGatedFinalizeStateMachine");
        }
    }

    @Test
    void broadExecutorSupportsMustRemainLegacyOnly() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(legacyExecutor).contains("Broad support is intentional only for LEGACY_REGULATED_MUTATION compatibility");
        assertThat(evidenceExecutor).contains("action == AuditAction.SUBMIT_ANALYST_DECISION");
        assertThat(evidenceExecutor).contains("resourceType == AuditResourceType.ALERT");
        assertThat(evidenceExecutor).doesNotContain("return action != null && resourceType != null");
    }

    @Test
    void fdp31ExecutorsMustUseSharedClaimConflictAndReplayPolicies() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(legacyExecutor).doesNotContain("findAndModify(");
        assertThat(evidenceExecutor).doesNotContain("findAndModify(");
        assertThat(legacyExecutor).contains("RegulatedMutationClaimService");
        assertThat(evidenceExecutor).contains("RegulatedMutationClaimService");
        assertThat(legacyExecutor).contains("RegulatedMutationConflictPolicy");
        assertThat(evidenceExecutor).contains("RegulatedMutationConflictPolicy");
        assertThat(legacyExecutor).contains("RegulatedMutationReplayResolver");
        assertThat(evidenceExecutor).contains("RegulatedMutationReplayResolver");
        assertThat(legacyExecutor).contains("replayResolver.resolve(document");
        assertThat(evidenceExecutor).contains("replayResolver.resolve(document");
        assertThat(legacyExecutor).doesNotContain("private <R, S> RegulatedMutationCommandDocument existingOrConflict");
        assertThat(evidenceExecutor).doesNotContain("private <R, S> RegulatedMutationCommandDocument existingOrConflict");
        assertThat(legacyExecutor).doesNotContain("leaseExpired(");
        assertThat(evidenceExecutor).doesNotContain("leaseExpired(");
    }

    @Test
    void fdp31ClaimServiceMustBeOnlyDirectMongoClaimBoundary() throws Exception {
        String claimService = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationClaimService.java"
        ));

        assertThat(claimService).contains("findAndModify(");
        assertThat(claimService).contains("execution_status");
        assertThat(claimService).contains("lease_expires_at");
        assertThat(claimService).contains("attempt_count");
        assertThat(claimService).doesNotContain("command.mutation().execute");
        assertThat(claimService).doesNotContain("auditPhaseService.recordPhase");
        assertThat(claimService).doesNotContain("transactionRunner.runLocalCommit");
        assertThat(claimService).doesNotContain("RegulatedMutationLocalAuditPhaseWriter");
        assertThat(claimService).doesNotContain("FraudDecisionEventPublisher");
        assertThat(claimService).doesNotContain("KafkaTemplate");
    }

    @Test
    void fdp31ReplayResolverMustRemainPureDecisionLogic() throws Exception {
        String replayResolver = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationReplayResolver.java"
        ));
        String legacyPolicy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationReplayPolicy.java"
        ));
        String evidencePolicy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeReplayPolicy.java"
        ));
        String registry = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationReplayPolicyRegistry.java"
        ));

        assertThat(replayResolver).contains("policyRegistry.resolve");
        assertThat(replayResolver).doesNotContain("resolveLegacy");
        assertThat(replayResolver).doesNotContain("resolveEvidenceGated");
        assertThat(legacyPolicy).contains("implements RegulatedMutationReplayPolicy");
        assertThat(evidencePolicy).contains("implements RegulatedMutationReplayPolicy");
        assertThat(evidencePolicy).contains("FINALIZE_RECOVERY_REQUIRED");
        assertThat(registry).contains("No regulated mutation replay policy registered");
        assertThat(registry).contains("Duplicate regulated mutation replay policy");
        assertThat(replayResolver).doesNotContain("commandRepository.save");
        assertThat(replayResolver).doesNotContain("mongoTemplate.findAndModify");
        assertThat(replayResolver).doesNotContain("auditPhaseService.recordPhase");
        assertThat(replayResolver).doesNotContain("transactionRunner.runLocalCommit");
        assertThat(replayResolver).doesNotContain("command.mutation().execute");
        assertThat(legacyPolicy).doesNotContain("commandRepository.save");
        assertThat(evidencePolicy).doesNotContain("commandRepository.save");
        assertThat(legacyPolicy).doesNotContain("auditPhaseService.recordPhase");
        assertThat(evidencePolicy).doesNotContain("auditPhaseService.recordPhase");
    }

    @Test
    void fdp31ConflictPolicyMustNotWriteOrExecuteMutation() throws Exception {
        String conflictPolicy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationConflictPolicy.java"
        ));

        assertThat(conflictPolicy).contains("ConflictingIdempotencyKeyException");
        assertThat(conflictPolicy).doesNotContain("commandRepository.save");
        assertThat(conflictPolicy).doesNotContain("mongoTemplate");
        assertThat(conflictPolicy).doesNotContain("findAndModify");
        assertThat(conflictPolicy).doesNotContain("auditPhaseService");
        assertThat(conflictPolicy).doesNotContain("command.mutation().execute");
    }

    @Test
    void fdp31DocsMustDescribePolicyExtractionWithoutReviewNotes() throws Exception {
        String source = Files.readString(Path.of("../docs/FDP-31-claim-replay-policy-extraction.md"));

        assertThat(source).contains("behavior-preserving refactor");
        assertThat(source).contains("no public API status changes");
        assertThat(source).contains("does not change mutation states");
        assertThat(source).contains("no transaction boundary changes");
        assertThat(source).contains("does not add external finality");
        assertThat(source).contains("RECOVERY_REQUIRED must win over responseSnapshot");
        assertThat(source).contains("FINALIZE_RECOVERY_REQUIRED must win over responseSnapshot");
        assertThat(source).contains("Rejected terminal states must win over responseSnapshot replay.");
        assertThat(source).contains("FDP-29 remains disabled by default");
        assertThat(source).contains("FDP-32 owns Regulated Mutation Lease Fencing & Stale Worker Protection");
        assertThat(source).doesNotContain("Merge Decision");
        assertThat(source).doesNotContain("GO:");
        assertThat(source).doesNotContain("NO-GO:");
        assertThat(source).doesNotContain("reviewer");
    }

    @Test
    void fdp31DocsMustNotClaimLeaseFencing() throws Exception {
        String source = Files.readString(Path.of("../docs/FDP-31-claim-replay-policy-extraction.md"));

        assertThat(source).contains("Claim Acquisition Is Not Write Fencing");
        assertThat(source).contains("FDP-31 does not implement lease-owner write fencing");
        assertThat(source).contains("claim acquisition is not write fencing");
        assertThat(source).contains("commandId + leaseOwner + unexpired lease");
        assertThat(source).contains("FDP-32");
        assertThat(source).doesNotContain("lease safety solved");
        assertThat(source).doesNotContain("stale worker writes prevented");
        assertThat(source).doesNotContain("fenced writes implemented");
        assertThat(source).doesNotContain("solves stale worker writes");
        assertThat(source).doesNotContain("prevents all stale writes");
        assertThat(source).doesNotContain("lease fencing implemented");
        assertThat(source).doesNotContain("write fencing implemented");
        assertThat(source).doesNotContain("fully fenced lease");
    }

    @Test
    void fdp32ClaimedTransitionsMustUseFencedCommandWriter() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String writer = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationFencedCommandWriter.java"
        ));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"
        ));

        assertThat(legacyExecutor).contains("RegulatedMutationFencedCommandWriter");
        assertThat(evidenceExecutor).contains("RegulatedMutationFencedCommandWriter");
        assertThat(legacyExecutor).contains("fencedCommandWriter.transition");
        assertThat(evidenceExecutor).contains("fencedCommandWriter.transition");
        assertThat(legacyExecutor).contains("fencedCommandWriter.validateActiveLease");
        assertThat(evidenceExecutor).contains("fencedCommandWriter.validateActiveLease");
        assertThat(writer).contains("lease_owner");
        assertThat(writer).contains("lease_expires_at");
        assertThat(writer).contains("state");
        assertThat(writer).contains("execution_status");
        assertThat(writer).contains("StaleRegulatedMutationLeaseException");
        assertThat(coordinator).doesNotContain("RegulatedMutationFencedCommandWriter");
        assertThat(coordinator).doesNotContain("lease_owner");
        assertThat(coordinator).doesNotContain("lease_expires_at");
    }

    @Test
    void regulatedMutationHandlersMustNotDependOnAuditOrBrokerBoundariesAtTypeLevel() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.frauddetection.alert");

        noClasses().that().resideInAPackage("..regulated.mutation..")
                .should().dependOnClassesThat().haveNameMatching(".*\\.audit\\.AuditService")
                .check(classes);
        noClasses().that().resideInAPackage("..regulated.mutation..")
                .should().dependOnClassesThat().haveNameMatching(".*\\.audit\\.AuditEventPublisher")
                .check(classes);
        noClasses().that().resideInAPackage("..regulated.mutation..")
                .should().dependOnClassesThat().haveNameMatching("org\\.springframework\\.kafka\\.core\\.KafkaTemplate")
                .check(classes);
    }

    @Test
    void fdp32ExecutorsMustNotUseRepositorySaveForStateTransitions() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String writer = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationFencedCommandWriter.java"
        ));

        assertThat(legacyExecutor).doesNotContain("commandRepository.save(");
        assertThat(evidenceExecutor).doesNotContain("commandRepository.save(");
        assertThat(legacyExecutor).contains("fencedCommandWriter.recoveryTransition");
        assertThat(evidenceExecutor).contains("fencedCommandWriter.recoveryTransition");
        assertThat(writer).contains("recoveryTransition(");
        assertThat(writer).contains("Only for non-claimed replay/recovery repair paths");
        assertThat(writer).contains("Claimed worker transitions must use");
        assertThat(writer).contains("RegulatedMutationRecoveryWriteConflictException");
        assertThat(writer).contains("execution_status").contains("PROCESSING");
    }

    @Test
    void fdp32AllowedFieldUpdatesMustNotBeGeneralMutationApi() throws Exception {
        String writer = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationFencedCommandWriter.java"
        ));
        String docs = Files.readString(Path.of("../docs/FDP-32-lease-fencing-stale-worker-protection.md"));

        assertThat(writer).contains("PROTECTED_UPDATE_FIELDS");
        assertThat(writer).contains("\"lease_owner\"");
        assertThat(writer).contains("\"idempotency_key\"");
        assertThat(writer).contains("\"request_hash\"");
        assertThat(writer).contains("\"mutation_model_version\"");
        assertThat(writer).contains("validateProtectedFieldsUnchanged");
        assertThat(docs).contains("`allowedFieldUpdates` is not a general document mutation API");
        assertThat(docs).contains("Identity, lease, ownership, idempotency, request, resource, action, creation, attempt-count, and mutation-model fields are immutable");
    }

    @Test
    void fdp33LeaseRenewalMustNotBePublicApiOrControllerDependency() throws Exception {
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            javaFiles = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        for (Path path : javaFiles) {
            String normalized = path.toString().replace('\\', '/');
            if (!normalized.endsWith("Controller.java")) {
                continue;
            }
            String source = Files.readString(path);
            assertThat(source)
                    .as("controllers must not expose or depend on FDP-33 lease renewal: " + path)
                    .doesNotContain("RegulatedMutationLeaseRenewalService")
                    .doesNotContain("lease-renew")
                    .doesNotContain("renewLease")
                    .doesNotContain("/renew");
        }
    }

    @Test
    void fdp33LeaseRenewalMustStayOutOfPublicApiAndPublishingBoundariesAtTypeLevel() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.frauddetection.alert");

        noClasses().that().resideInAnyPackage("..controller..", "..api..")
                .should().dependOnClassesThat().haveNameMatching(".*RegulatedMutationLeaseRenewal.*")
                .check(classes);
        noClasses().that().resideInAnyPackage("..outbox..", "..messaging..", "..regulated.mutation..")
                .should().dependOnClassesThat().haveNameMatching(".*RegulatedMutationLeaseRenewalService")
                .check(classes);
        noClasses().that().haveNameMatching(".*RegulatedMutationLeaseRenewal(Service|Policy|FailureHandler)")
                .should().dependOnClassesThat().haveNameMatching(".*(AuditService|AuditEventPublisher|FraudDecisionEventPublisher|KafkaTemplate|TransactionalOutboxRecordRepository|AlertRepository|TrustAuthority|ExternalAnchorPublisher).*")
                .check(classes);
    }

    @Test
    void fdp33LeaseRenewalMustOnlyUpdateLeaseMetadata() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalService.java"
        ));

        assertThat(service).contains("mongoTemplate.updateFirst");
        assertThat(service).contains("\"lease_expires_at\"");
        assertThat(service).contains("\"last_heartbeat_at\"");
        assertThat(service).contains("\"last_lease_renewed_at\"");
        assertThat(service).contains("\"lease_budget_started_at\"");
        assertThat(service).contains("\"lease_renewal_count\"");
        assertThat(service)
                .doesNotContain("\"idempotency_key\"")
                .doesNotContain("\"request_hash\"")
                .doesNotContain("\"actor_id\"")
                .doesNotContain("\"resource_id\"")
                .doesNotContain("\"action\"")
                .doesNotContain("\"resource_type\"")
                .doesNotContain("\"response_snapshot\"")
                .doesNotContain("\"outbox_event_id\"")
                .doesNotContain("\"local_commit_marker\"")
                .doesNotContain("\"success_audit_id\"")
                .doesNotContain("\"public_status\"")
                .doesNotContain(".set(\"state\"")
                .doesNotContain(".set(\"execution_status\"");
    }

    @Test
    void fdp33BudgetExceededRecoveryHandlerMustOnlyMarkRecoveryFields() throws Exception {
        String handler = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalFailureHandler.java"
        ));

        assertThat(handler).contains("mongoTemplate.updateFirst");
        assertThat(handler).contains("\"_id\"");
        assertThat(handler).contains("\"lease_owner\"");
        assertThat(handler).contains("\"lease_expires_at\"");
        assertThat(handler).contains("\"execution_status\"");
        assertThat(handler).contains("\"state\"");
        assertThat(handler).contains("mutation_model_version");
        assertThat(handler).contains(".set(\"execution_status\"");
        assertThat(handler).contains(".set(\"degradation_reason\"");
        assertThat(handler).contains(".set(\"last_error\"");
        assertThat(handler).contains(".set(\"updated_at\"");
        assertThat(handler).contains(".set(\"last_heartbeat_at\"");
        assertThat(handler).contains("publicStatusMapper.submitDecisionStatus");
        assertThat(handler)
                .doesNotContain("\"idempotency_key\"")
                .doesNotContain("\"request_hash\"")
                .doesNotContain("\"actor_id\"")
                .doesNotContain("\"resource_id\"")
                .doesNotContain("\"action\"")
                .doesNotContain("\"resource_type\"")
                .doesNotContain("\"response_snapshot\"")
                .doesNotContain("\"outbox_event_id\"")
                .doesNotContain("\"local_commit_marker\"")
                .doesNotContain("\"success_audit_id\"")
                .doesNotContain("\"success_audit_recorded\"");
    }

    @Test
    void fdp33LeaseRenewalMustStayAwayFromBrokerOutboxAuditAndBusinessBoundaries() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalService.java"
        ));
        String policy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalPolicy.java"
        ));
        String handler = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalFailureHandler.java"
        ));

        assertThat(service)
                .doesNotContain("KafkaTemplate")
                .doesNotContain("FraudDecisionEventPublisher")
                .doesNotContain("TransactionalOutbox")
                .doesNotContain("ExternalAudit")
                .doesNotContain("AuditService")
                .doesNotContain("AuditEventPublisher")
                .doesNotContain("recoveryTransition(")
                .doesNotContain("AlertRepository")
                .doesNotContain("TrustAuthority")
                .doesNotContain("ExternalAnchorPublisher")
                .doesNotContain("command.mutation().execute");
        assertThat(policy)
                .doesNotContain("MongoTemplate")
                .doesNotContain("AlertRepository")
                .doesNotContain("AuditService")
                .doesNotContain("TransactionalOutbox")
                .doesNotContain("KafkaTemplate")
                .doesNotContain("command.mutation().execute");
        assertThat(handler)
                .doesNotContain("KafkaTemplate")
                .doesNotContain("FraudDecisionEventPublisher")
                .doesNotContain("TransactionalOutbox")
                .doesNotContain("ExternalAudit")
                .doesNotContain("AuditService")
                .doesNotContain("AuditEventPublisher")
                .doesNotContain("AlertRepository")
                .doesNotContain("TrustAuthority")
                .doesNotContain("ExternalAnchorPublisher")
                .doesNotContain("RegulatedMutationLeaseRenewalService")
                .doesNotContain("command.mutation().execute");
    }

    @Test
    void fdp33LeaseRenewalModelPolicySeamMustOwnModelStateTables() throws Exception {
        String policy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalPolicy.java"
        ));
        String legacy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyLeaseRenewalModelPolicy.java"
        ));
        String evidence = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedLeaseRenewalModelPolicy.java"
        ));

        assertThat(policy).contains("RegulatedMutationLeaseRenewalModelPolicy");
        assertThat(policy).contains("Duplicate regulated mutation lease renewal model policy");
        assertThat(policy).contains("Missing regulated mutation lease renewal model policy");
        assertThat(policy).doesNotContain("LEGACY_RENEWABLE_STATES");
        assertThat(policy).doesNotContain("EVIDENCE_GATED_RENEWABLE_STATES");
        assertThat(legacy).contains("RegulatedMutationState.REQUESTED");
        assertThat(legacy).contains("RegulatedMutationState.BUSINESS_COMMITTED");
        assertThat(evidence).contains("RegulatedMutationState.FINALIZING");
        assertThat(evidence).contains("RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED");
    }

    @Test
    void fdp33ExecutorsMustNotRenewLeasesDirectlyOrUpdateLeaseExpiryWithMongo() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(legacyExecutor)
                .doesNotContain("RegulatedMutationLeaseRenewalService")
                .doesNotContain(".set(\"lease_expires_at\"");
        assertThat(evidenceExecutor)
                .doesNotContain("RegulatedMutationLeaseRenewalService")
                .doesNotContain(".set(\"lease_expires_at\"");
    }

    @Test
    void fdp34CheckpointRenewalMustNotBeSchedulerPublicApiOrBoundaryLeak() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationCheckpointRenewalService.java"
        ));
        String policy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationSafeCheckpointPolicy.java"
        ));
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            javaFiles = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        assertThat(service)
                .contains("beforeAttemptedAudit")
                .contains("beforeLegacyBusinessCommit")
                .contains("beforeEvidenceGatedFinalize")
                .contains("leaseRenewalService.renew")
                .doesNotContain("@Scheduled")
                .doesNotContain("TaskScheduler")
                .doesNotContain("ScheduledExecutorService")
                .doesNotContain("while (true)")
                .doesNotContain("Thread.sleep")
                .doesNotContain("Flux.interval")
                .doesNotContain("Timer")
                .doesNotContain("KafkaTemplate")
                .doesNotContain("FraudDecisionEventPublisher")
                .doesNotContain("TransactionalOutboxRecordRepository")
                .doesNotContain("AuditService")
                .doesNotContain("AuditEventPublisher")
                .doesNotContain("AlertRepository")
                .doesNotContain("TrustAuthority")
                .doesNotContain("ExternalAnchorPublisher")
                .doesNotContain("command.mutation().execute");
        assertThat(policy).contains("RegulatedMutationRenewalCheckpoint");
        assertThat(policy).contains("BEFORE_ATTEMPTED_AUDIT");
        assertThat(policy).contains("AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE");

        for (Path path : javaFiles) {
            String normalized = path.toString().replace('\\', '/');
            if (!normalized.endsWith("Controller.java")) {
                continue;
            }
            String source = Files.readString(path);
            assertThat(source)
                    .as("controllers must not expose FDP-34 checkpoint renewal: " + path)
                    .doesNotContain("RegulatedMutationCheckpointRenewalService")
                    .doesNotContain("RegulatedMutationLeaseRenewalService")
                    .doesNotContain("heartbeat")
                    .doesNotContain("checkpoint-renew")
                    .doesNotContain("/renew");
        }
    }

    @Test
    void fdp34ExecutorsMustUseNamedCheckpointMethodsInsteadOfDirectRenewal() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(legacyExecutor)
                .contains("checkpointRenewalService.beforeAttemptedAudit")
                .contains("checkpointRenewalService.beforeLegacyBusinessCommit")
                .contains("checkpointRenewalService.beforeSuccessAuditRetry")
                .doesNotContain("leaseRenewalService.renew")
                .doesNotContain("RegulatedMutationLeaseRenewalService");
        assertThat(evidenceExecutor)
                .contains("checkpointRenewalService.beforeEvidencePreparation")
                .contains("checkpointRenewalService.afterEvidencePreparedBeforeFinalize")
                .contains("checkpointRenewalService.beforeEvidenceGatedFinalize")
                .doesNotContain("leaseRenewalService.renew")
                .doesNotContain("RegulatedMutationLeaseRenewalService");
    }

    @Test
    void fdp34DocsMustDescribeCheckpointAdoptionWithoutReviewNotes() throws Exception {
        String architecture = Files.readString(Path.of("../docs/architecture/FDP-34-safe-checkpoint-adoption.md"));
        String checkpoints = Files.readString(Path.of("../docs/architecture/FDP-34-safe-checkpoints.md"));
        String runbook = Files.readString(Path.of("../docs/runbooks/FDP-34-safe-checkpoint-renewal-runbook.md"));
        String mergeGate = Files.readString(Path.of("../docs/FDP-34-merge-gate.md"));
        String combined = architecture + "\n" + checkpoints + "\n" + runbook + "\n" + mergeGate;

        assertThat(combined).contains("Renewal preserves ownership, not progress");
        assertThat(combined).contains("No generic heartbeat system");
        assertThat(combined).contains("No automatic infinite renewal loop");
        assertThat(combined).contains("Checkpoint renewal failure stops execution");
        assertThat(combined).contains("BEFORE_ATTEMPTED_AUDIT");
        assertThat(combined).contains("BEFORE_LEGACY_BUSINESS_COMMIT");
        assertThat(combined).contains("BEFORE_EVIDENCE_GATED_FINALIZE");
        assertThat(combined).contains("Worker renewing but not progressing");
        assertThat(combined).contains("do not bypass checkpoint renewal");
        assertThat(combined).contains("do not increase lease budget blindly");
        assertThat(combined).contains("no public heartbeat endpoint");
        assertThat(combined).contains("no distributed lock");
        assertThat(combined).contains("does not enable production or bank behavior by itself");
        assertThat(combined).doesNotContain("Merge Decision");
        assertThat(combined).doesNotContain("GO:");
        assertThat(combined).doesNotContain("NO-GO:");
        assertThat(combined).doesNotContain("reviewer");
    }

    @Test
    void fdp32DocsMustDescribeLeaseFencingWithoutReviewNotes() throws Exception {
        String architecture = Files.readString(Path.of("../docs/FDP-32-lease-fencing-stale-worker-protection.md"));
        String mergeGate = Files.readString(Path.of("../docs/FDP-32-merge-gate.md"));
        String combined = architecture + "\n" + mergeGate;

        assertThat(combined).contains("claim acquisition is not write fencing");
        assertThat(combined).contains("post-claim transitions are fenced");
        assertThat(combined).contains("command transition fencing is not business-side-effect rollback by itself");
        assertThat(combined).contains("transaction-mode REQUIRED");
        assertThat(combined).contains("transaction-mode OFF is compatibility behavior");
        assertThat(combined).contains("transaction-mode REQUIRED is required for bank-grade stale-worker business-write safety");
        assertThat(combined).contains("stale worker");
        assertThat(combined).contains("no silent repository.save after claim");
        assertThat(combined).contains("does not expand transaction scope");
        assertThat(combined).contains("no distributed lock");
        assertThat(combined).contains("Source-string architecture tests are guardrails, not complete architectural proof");
        assertThat(combined).contains("FDP-32 is merge-safe as lease-owner fenced command transition hardening");
        assertThat(combined).doesNotContain("Merge Decision");
        assertThat(combined).doesNotContain("GO:");
        assertThat(combined).doesNotContain("NO-GO:");
        assertThat(combined).doesNotContain("reviewer");
    }

    @Test
    void fdp33DocsMustDescribeBoundedRenewalWithoutReviewNotes() throws Exception {
        String runbook = Files.readString(Path.of("../docs/FDP-33-lease-renewal-operational-readiness.md"));
        String mergeGate = Files.readString(Path.of("../docs/FDP-33-merge-gate.md"));
        String operatorRunbook = Files.readString(Path.of("../docs/runbooks/FDP-33-lease-renewal-runbook.md"));
        String dashboard = Files.readString(Path.of("../docs/observability/FDP-33-lease-renewal-dashboard.md"));
        String combined = runbook + "\n" + mergeGate + "\n" + operatorRunbook + "\n" + dashboard;

        assertThat(combined).contains("owner-fenced");
        assertThat(combined).contains("bounded");
        assertThat(combined).contains("lease_expires_at > now");
        assertThat(combined).contains("Renewal Caller Contract");
        assertThat(combined).contains("Runtime Adoption Contract");
        assertThat(combined).contains("Lease renewal is not a guarantee of progress");
        assertThat(combined).contains("does not automatically call renewal from current executors");
        assertThat(combined).contains("future runtime adoption must add explicit safe checkpoints");
        assertThat(combined).contains("Missing renewal metadata is backward compatible");
        assertThat(combined).contains("LEASE_RENEWAL_BUDGET_EXCEEDED");
        assertThat(combined).contains("Recovery status wins over `responseSnapshot`");
        assertThat(combined).contains("Legacy Renewable State Justification");
        assertThat(combined).contains("must not be used as idle queue parking");
        assertThat(combined).contains("execution_status and recovery precedence remain authoritative");
        assertThat(combined).contains("REQUESTED");
        assertThat(combined).contains("AUDIT_ATTEMPTED");
        assertThat(combined).contains("BUSINESS_COMMITTING");
        assertThat(combined).contains("BUSINESS_COMMITTED");
        assertThat(combined).contains("SUCCESS_AUDIT_PENDING");
        assertThat(combined).contains("INVALID_EXTENSION");
        assertThat(combined).contains("COMMAND_NOT_FOUND");
        assertThat(combined).contains("MODEL_VERSION_MISMATCH");
        assertThat(combined).contains("EXECUTION_STATUS_MISMATCH");
        assertThat(combined).contains("processing duration p95/p99");
        assertThat(combined).contains("commands renewing but not progressing");
        assertThat(combined).contains("Renewal can preserve ownership but cannot prove progress");
        assertThat(combined).contains("command id");
        assertThat(combined).contains("alert id");
        assertThat(combined).contains("actor id");
        assertThat(combined).contains("lease owner");
        assertThat(combined).contains("idempotency key");
        assertThat(combined).contains("request hash");
        assertThat(combined).contains("resource id");
        assertThat(combined).contains("exception message");
        assertThat(combined).contains("raw path");
        assertThat(combined).contains("token");
        assertThat(combined).contains("Worker stuck but renewing");
        assertThat(combined).contains("Budget exceeded flood");
        assertThat(combined).contains("do not increase budget blindly");
        assertThat(combined).contains("do not bypass fencing");
        assertThat(combined).contains("does not enable FDP-29 production mode");
        assertThat(combined).contains("no distributed lock");
        assertThat(combined).contains("no public heartbeat endpoint");
        assertThat(combined).contains("does not provide external finality");
        assertThat(combined).contains("cannot create infinite `PROCESSING`");
        assertThat(combined).contains("Do not manually rewrite lease_owner.");
        assertThat(combined).contains("Do not manually extend expired leases.");
        assertThat(combined).contains("Do not mark evidence confirmed manually.");
        assertThat(combined).contains("Do not edit business aggregate directly.");
        assertThat(combined).contains("Do not submit a new idempotency key");
        assertThat(combined).contains("Do not disable fencing/renewal guards to clear backlog.");
        assertThat(combined).doesNotContain("Merge Decision");
        assertThat(combined).doesNotContain("GO:");
        assertThat(combined).doesNotContain("NO-GO:");
        assertThat(combined).doesNotContain("reviewer");
    }

    @Test
    void evidenceGatedFinalizeExecutorMustDeclareExecutorModelVersion() throws Exception {
        String executor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(executor).contains("implements RegulatedMutationExecutor");
        assertThat(executor).contains("return RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1");
    }

    @Test
    void legacyExecutorMustDeclareExecutorModelVersion() throws Exception {
        String executor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));

        assertThat(executor).contains("implements RegulatedMutationExecutor");
        assertThat(executor).contains("return RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION");
    }

    @Test
    void fdp30DocsMustDescribeArchitectureScopeWithoutReviewNotes() throws Exception {
        String source = Files.readString(Path.of("../docs/FDP-30-executor-split.md"));

        assertThat(source).contains("# FDP-30 Regulated Mutation Executor Split");
        assertThat(source).contains("## Scope");
        assertThat(source).contains("## Non-Goals");
        assertThat(source).contains("## Production Wiring");
        assertThat(source).contains("## Behavior-Preservation Contract");
        assertThat(source).contains("FDP-29 remains disabled by default");
        assertThat(source).contains("FDP-30 does not change local ACID boundaries");
        assertThat(source).contains("Null `mutation_model_version`");
        assertThat(source).contains("`SUCCESS_AUDIT_PENDING` retry");
        assertThat(source).contains("`RECOVERY_REQUIRED` precedence");
        assertThat(source).contains("Active `PROCESSING` lease");
        assertThat(source).contains("broad `supports(action, resourceType)`");
        assertThat(source).contains("Registry Fail-Closed Behavior");
        assertThat(source).contains("registry is not a replacement for FDP-29 startup guard");
        assertThat(source).doesNotContain("Merge Decision");
        assertThat(source).doesNotContain("GO:");
        assertThat(source).doesNotContain("NO-GO:");
        assertThat(source).doesNotContain("reviewer");
    }

    @Test
    void localAuditPhaseWriterMustNotFanOutToAuditPublishers() throws Exception {
        String writer = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/audit/RegulatedMutationLocalAuditPhaseWriter.java"
        ));

        assertThat(writer).contains("AuditEventRepository");
        assertThat(writer).contains("AuditAnchorRepository");
        assertThat(writer).doesNotContain("AuditService");
        assertThat(writer).doesNotContain("AuditEventPublisher");
        assertThat(writer).doesNotContain("ExternalAuditAnchorPublisher");
        assertThat(writer).doesNotContain("KafkaTemplate");
        assertThat(writer).doesNotContain(".publish(");
    }

    @Test
    void localAuditPhaseWriterMustOnlyBeUsedByFdp29CoordinatorPath() throws Exception {
        List<Path> javaFiles;
        try (java.util.stream.Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            javaFiles = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/')
                            .endsWith("audit/RegulatedMutationLocalAuditPhaseWriter.java"))
                    .toList();
        }

        for (Path path : javaFiles) {
            String normalized = path.toString().replace('\\', '/');
            String source = Files.readString(path);
            if (normalized.endsWith("regulated/EvidenceGatedFinalizeExecutor.java")
                    || normalized.endsWith("regulated/EvidenceGatedFinalizeStartupGuard.java")) {
                assertThat(source).contains("RegulatedMutationLocalAuditPhaseWriter");
                continue;
            }
            assertThat(source)
                    .as("RegulatedMutationLocalAuditPhaseWriter must not leak outside FDP-29 coordinator: " + path)
                    .doesNotContain("RegulatedMutationLocalAuditPhaseWriter");
        }
    }

    @Test
    void legacyAuditPathsMustContinueUsingPhaseAuditService() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));
        String writeSuccessAudit = legacyExecutor.substring(
                legacyExecutor.indexOf("private <R, S> S writeSuccessAudit("),
                legacyExecutor.indexOf("private <R, S> void recordPostCommitDegraded(")
        );
        String retrySuccessAuditOnly = legacyExecutor.substring(
                legacyExecutor.indexOf("private <R, S> RegulatedMutationResult<S> retrySuccessAuditOnly("),
                legacyExecutor.indexOf("private <R, S> S writeSuccessAudit(")
        );

        assertThat(writeSuccessAudit).contains("auditPhaseService.recordPhase");
        assertThat(writeSuccessAudit).doesNotContain("localSuccessAudit(");
        assertThat(retrySuccessAuditOnly).contains("auditPhaseService.recordPhase");
        assertThat(retrySuccessAuditOnly).doesNotContain("localSuccessAudit(");
    }

    @Test
    void legacyExecutorMustNotUseLocalAuditPhaseWriter() throws Exception {
        String legacyExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor.java"
        ));

        assertThat(legacyExecutor).doesNotContain("RegulatedMutationLocalAuditPhaseWriter");
        assertThat(legacyExecutor).doesNotContain("localSuccessAudit(");
    }

    @Test
    void trustIncidentReadsMustRemainReadOnly() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/trust/TrustIncidentService.java"
        ));
        String controller = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/trust/TrustIncidentController.java"
        ));
        String listMethod = service.substring(service.indexOf("public List<TrustIncidentResponse> listOpen()"),
                service.indexOf("public TrustIncidentResponse acknowledge("));
        String summaryMethod = service.substring(service.indexOf("public TrustIncidentSummary summary()"),
                service.indexOf("private RegulatedMutationIntent intent("));
        String previewMethod = controller.substring(controller.indexOf("public TrustSignalPreviewResponse preview("),
                controller.indexOf("@PostMapping(\"/refresh\")"));

        assertThat(listMethod).doesNotContain("repository.save");
        assertThat(listMethod).doesNotContain("materializer.materialize");
        assertThat(summaryMethod).doesNotContain("repository.save");
        assertThat(summaryMethod).doesNotContain("materializer.materialize");
        assertThat(previewMethod).doesNotContain("repository.save");
        assertThat(previewMethod).doesNotContain("materializer.materialize");
        assertThat(previewMethod).doesNotContain("service.refresh");
        assertThat(previewMethod).doesNotContain("regulatedMutationCoordinator");
    }

    @Test
    void trustIncidentRefreshMustUseCoordinatorAndNotManualAudit() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/trust/TrustIncidentService.java"
        ));
        String refreshMethod = service.substring(service.indexOf("public TrustIncidentMaterializationResponse refresh("),
                service.indexOf("public TrustIncidentSummary summary()"));

        assertThat(refreshMethod).contains("regulatedMutationCoordinator.commit(command)");
        assertThat(refreshMethod).contains("AuditAction.REFRESH_TRUST_INCIDENTS");
        assertThat(refreshMethod).doesNotContain("auditService");
        assertThat(refreshMethod).doesNotContain("AuditOutcome.SUCCESS");
        assertThat(refreshMethod).doesNotContain("AuditOutcome.ATTEMPTED");
    }

    @Test
    void systemTrustLevelMustNotMaterializeTrustIncidents() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/system/SystemTrustLevelController.java"
        ));

        assertThat(source).doesNotContain("materializer.materialize");
        assertThat(source).doesNotContain("trustSignalCollector.collect()");
        assertThat(source).contains("trustIncidentService.summary()");
    }

    @Test
    void decisionOutboxWriterMustPersistTransactionalOutboxRecord() throws Exception {
        String writer = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/service/DecisionOutboxWriter.java"
        ));

        assertThat(writer).contains("TransactionalOutboxRecordRepository");
        assertThat(writer).contains("outboxRepository.save(record");
        assertThat(writer).contains("TransactionalOutboxRecordRepository is required");
    }

    @Test
    void docsMustNotOverclaimFdp26() throws Exception {
        String readme = Files.readString(Path.of("../README.md"));
        String api = Files.readString(Path.of("../docs/api-surface-v1.md"));
        String security = Files.readString(Path.of("../docs/security-foundation-v1.md"));
        String combined = readme + "\n" + api + "\n" + security;

        assertForbiddenPhraseIsContextual(combined, "exactly once");
        assertForbiddenPhraseIsContextual(combined, "exactly-once");
        assertForbiddenPhraseIsContextual(combined, "distributed ACID");
        assertForbiddenPhraseIsContextual(combined, "full ACID");
        assertForbiddenPhraseIsContextual(combined, "pre-commit finalize implemented");
        assertForbiddenPhraseIsContextual(combined, "pre-commit/finalize");
        assertForbiddenPhraseIsContextual(combined, "cannot mutate before evidence");
        assertForbiddenPhraseIsContextual(combined, "notarized");
        assertForbiddenPhraseIsContextual(combined, "notarization");
        assertForbiddenPhraseIsContextual(combined, "WORM");
        assertForbiddenPhraseIsContextual(combined, "regulator certified");
        assertForbiddenPhraseIsContextual(combined, "regulator-certified");
        assertThat(combined).contains("not distributed ACID");
        assertThat(combined).contains("does not provide exactly-once");
    }

    @Test
    void fdp29DocsMustDescribeCurrentLocalScopeAndTargetGaps() throws Exception {
        String readme = Files.readString(Path.of("../README.md"));
        String adr = Files.readString(Path.of("../docs/adr/FDP-29-evidence-gated-finalize.md"));
        String handoff = Files.readString(Path.of("../docs/FDP-29-evidence-gated-finalize-handoff.md"));
        String preconditions = Files.readString(Path.of("../docs/architecture/FDP-29-evidence-preconditions.md"));
        String openApi = Files.readString(Path.of("../docs/openapi/alert-service.openapi.yaml"));
        String combined = readme + "\n" + adr + "\n" + handoff + "\n" + preconditions + "\n" + openApi;

        assertThat(combined).contains("local evidence-precondition-gated finalize");
        assertThat(combined).contains("Current Implementation vs Target Design");
        assertThat(combined).contains("LOCAL_EVIDENCE_GATE_V1");
        assertThat(combined).contains("External anchor readiness");
        assertThat(combined).contains("Trust Authority signing readiness");
        assertThat(combined).contains("not part of the current local finalize transaction");
        assertThat(combined).contains("not distributed ACID");
    }

    @Test
    void fdp29DocsMustNotContainPromptDecisionNotes() throws Exception {
        List<Path> docs;
        try (java.util.stream.Stream<Path> stream = Files.walk(Path.of("../docs"))) {
            docs = stream
                    .filter(path -> path.toString().endsWith(".md"))
                    .toList();
        }

        for (Path doc : docs) {
            String source = Files.readString(doc);
            assertThat(source).as("project documentation must not contain prompt merge-decision notes: " + doc)
                    .doesNotContain("Merge Decision")
                    .doesNotContain("GO: design is internally consistent")
                    .doesNotContain("NO-GO: reviewers");
        }
    }

    private void assertForbiddenPhraseIsContextual(String source, String phrase) {
        String lowerSource = source.toLowerCase(java.util.Locale.ROOT);
        String lowerPhrase = phrase.toLowerCase(java.util.Locale.ROOT);
        int index = lowerSource.indexOf(lowerPhrase);
        while (index >= 0) {
            int start = Math.max(0, index - 220);
            int end = Math.min(lowerSource.length(), index + lowerPhrase.length() + 220);
            String context = lowerSource.substring(start, end);
            assertThat(context)
                    .as("Forbidden FDP-26 wording must be negated, limited, or future-contextual: " + phrase)
                    .containsAnyOf(
                            "does not",
                            "do not",
                            "not ",
                            "no ",
                            "never",
                            "must not",
                            "outside",
                            "future",
                            "deferred",
                            "not implemented",
                            "unless",
                            "durability guarantee",
                            "object lock",
                            "wording rules"
                    );
            index = lowerSource.indexOf(lowerPhrase, index + lowerPhrase.length());
        }
    }
}
