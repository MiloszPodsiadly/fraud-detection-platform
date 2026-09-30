package com.frauddetection.alert.regulated;

import com.frauddetection.alert.regulated.chaos.LiveRuntimeCheckpoint;
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
    void mainReadmeMustContainExactMaintainerContactBlock() throws Exception {
        String readme = Files.readString(Path.of("../README.md")).replace("\r\n", "\n");

        assertThat(readme).contains(
                "Milosz Podsiadly  \n"
                        + "[m.podsiadly99@gmail.com](mailto:m.podsiadly99@gmail.com)  \n"
                        + "[GitHub - MiloszPodsiadly](https://github.com/MiloszPodsiadly)"
        );
    }

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
        String evidenceExecutorSource = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String handlerSource = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/mutation/submitdecision/SubmitDecisionMutationHandler.java"
        ));

        assertThat(serviceSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(coordinatorSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(evidenceExecutorSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(handlerSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(serviceSource).doesNotContain(".publish(");
        assertThat(coordinatorSource).doesNotContain(".publish(");
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
    void canonicalFinalizeMustUseLocalSuccessAuditAndDurableFailureAudit() throws Exception {
        String executor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String finalizeMethod = executor.substring(
                executor.indexOf("private <R, S> RegulatedMutationResult<S> finalizeVisibleMutation("),
                executor.indexOf("private <R, S> RegulatedMutationResult<S> markRecoveryRequired(")
        );

        assertThat(finalizeMethod).contains("localSuccessAudit(command, document)");
        assertThat(finalizeMethod).contains("auditPhaseService.recordPhase");
        assertThat(finalizeMethod).contains("AuditOutcome.FAILED");
        assertThat(finalizeMethod).doesNotContain("AuditOutcome.SUCCESS");
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
        assertThat(coordinator).doesNotContain("LegacyRegulatedMutationExecutor");
        assertThat(coordinator).doesNotContain("ignoredEvidenceGatedFinalizeActive");
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
        assertThat(evidenceExecutor).contains("RegulatedMutationDefinitions.find(action, resourceType).isPresent()");
    }

    @Test
    void evidenceGatedOnlyBoundariesMustRemainInsideCurrentRuntime() throws Exception {
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
                    .as("Evidence-gated helper leaked outside current executor/startup boundary: " + path)
                    .doesNotContain("RegulatedMutationLocalAuditPhaseWriter")
                    .doesNotContain("EvidencePreconditionEvaluator")
                    .doesNotContain("EvidenceGatedFinalizeStateMachine");
        }
    }

    @Test
    void currentExecutorSupportsOnlyCataloguedOperations() throws Exception {
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(evidenceExecutor).contains("RegulatedMutationDefinitions.find(action, resourceType).isPresent()");
        assertThat(evidenceExecutor).doesNotContain("return action != null && resourceType != null");
    }

    @Test
    void executorsMustUseSharedClaimConflictAndReplayPolicies() throws Exception {
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(evidenceExecutor).doesNotContain("findAndModify(");
        assertThat(evidenceExecutor).contains("RegulatedMutationClaimService");
        assertThat(evidenceExecutor).contains("RegulatedMutationConflictPolicy");
        assertThat(evidenceExecutor).contains("RegulatedMutationReplayResolver");
        assertThat(evidenceExecutor).contains("replayResolver.resolve(document");
        assertThat(evidenceExecutor).doesNotContain("private <R, S> RegulatedMutationCommandDocument existingOrConflict");
        assertThat(evidenceExecutor).doesNotContain("leaseExpired(");
    }

    @Test
    void claimServiceMustBeOnlyDirectMongoClaimBoundary() throws Exception {
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
    void replayResolverMustRemainPureDecisionLogic() throws Exception {
        String replayResolver = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationReplayResolver.java"
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
        assertThat(evidencePolicy).contains("implements RegulatedMutationReplayPolicy");
        assertThat(evidencePolicy).contains("FINALIZE_RECOVERY_REQUIRED");
        assertThat(registry).contains("No regulated mutation replay policy registered");
        assertThat(registry).contains("Duplicate regulated mutation replay policy");
        assertThat(replayResolver).doesNotContain("commandRepository.save");
        assertThat(replayResolver).doesNotContain("mongoTemplate.findAndModify");
        assertThat(replayResolver).doesNotContain("auditPhaseService.recordPhase");
        assertThat(replayResolver).doesNotContain("transactionRunner.runLocalCommit");
        assertThat(replayResolver).doesNotContain("command.mutation().execute");
        assertThat(evidencePolicy).doesNotContain("commandRepository.save");
        assertThat(evidencePolicy).doesNotContain("auditPhaseService.recordPhase");
    }

    @Test
    void conflictPolicyMustNotWriteOrExecuteMutation() throws Exception {
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
    void claimedTransitionsMustUseFencedCommandWriter() throws Exception {
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String writer = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationFencedCommandWriter.java"
        ));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"
        ));

        assertThat(evidenceExecutor).contains("RegulatedMutationFencedCommandWriter");
        assertThat(evidenceExecutor).contains("fencedCommandWriter.transition");
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
    void executorsMustNotUseRepositorySaveForStateTransitions() throws Exception {
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String writer = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationFencedCommandWriter.java"
        ));

        assertThat(evidenceExecutor).doesNotContain("commandRepository.save(");
        assertThat(evidenceExecutor).contains("fencedCommandWriter.recoveryTransition");
        assertThat(writer).contains("recoveryTransition(");
        assertThat(writer).contains("Only for non-claimed replay/recovery repair paths");
        assertThat(writer).contains("Claimed worker transitions must use");
        assertThat(writer).contains("RegulatedMutationRecoveryWriteConflictException");
        assertThat(writer).contains("execution_status").contains("PROCESSING");
    }

    @Test
    void allowedFieldUpdatesMustNotBeGeneralMutationApi() throws Exception {
        String writer = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationFencedCommandWriter.java"
        ));
        String docs = Files.readString(Path.of("../docs/architecture/regulated_mutation_lease_fencing.md"));

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
    void leaseRenewalMustNotBePublicApiOrControllerDependency() throws Exception {
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
                    .as("controllers must not expose or depend on regulated mutation lease renewal: " + path)
                    .doesNotContain("RegulatedMutationLeaseRenewalService")
                    .doesNotContain("lease-renew")
                    .doesNotContain("renewLease")
                    .doesNotContain("/renew");
        }
    }

    @Test
    void leaseRenewalMustStayOutOfPublicApiAndPublishingBoundariesAtTypeLevel() {
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
    void leaseRenewalMustOnlyUpdateLeaseMetadata() throws Exception {
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
    void budgetExceededRecoveryHandlerMustOnlyMarkRecoveryFields() throws Exception {
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
    void leaseRenewalMustStayAwayFromBrokerOutboxAuditAndBusinessBoundaries() throws Exception {
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
    void readinessMustNotAddRegulatedMutationPublicSemantics() throws Exception {
        String statuses = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/api/SubmitDecisionOperationStatus.java"
        ));
        String states = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationState.java"
        ));
        String modelVersions = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationModelVersion.java"
        ));

        assertThat(enumValues(statuses)).containsExactly(
                "IN_PROGRESS",
                "RECOVERY_REQUIRED",
                "EVIDENCE_PREPARING",
                "EVIDENCE_PREPARED",
                "FINALIZING",
                "FINALIZED_VISIBLE",
                "FINALIZED_EVIDENCE_PENDING_EXTERNAL",
                "FINALIZED_EVIDENCE_CONFIRMED",
                "REJECTED_EVIDENCE_UNAVAILABLE",
                "FAILED_BUSINESS_VALIDATION",
                "FINALIZE_RECOVERY_REQUIRED"
        );
        assertThat(enumValues(states)).containsExactly(
                "REQUESTED",
                "EVIDENCE_PREPARING",
                "EVIDENCE_PREPARED",
                "FINALIZING",
                "FINALIZED_VISIBLE",
                "FINALIZED_EVIDENCE_PENDING_EXTERNAL",
                "FINALIZED_EVIDENCE_CONFIRMED",
                "REJECTED_EVIDENCE_UNAVAILABLE",
                "FAILED_BUSINESS_VALIDATION",
                "FINALIZE_RECOVERY_REQUIRED",
                "FAILED"
        );
        assertThat(enumValues(modelVersions)).containsExactly(
                "EVIDENCE_GATED_FINALIZE_V1"
        );
    }

    @Test
    void readinessMustNotExposeHeartbeatOrRenewalControllerSemantics() throws Exception {
        List<Path> controllers;
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            controllers = stream
                    .filter(path -> path.toString().endsWith("Controller.java"))
                    .toList();
        }

        for (Path controller : controllers) {
            String source = Files.readString(controller);
            assertThat(source)
                    .as("controller must not expose heartbeat/renewal semantics: " + controller)
                    .doesNotContain("RegulatedMutationLeaseRenewalService")
                    .doesNotContain("RegulatedMutationCheckpointRenewalService")
                    .doesNotContain("heartbeat")
                    .doesNotContain("renewLease")
                    .doesNotContain("checkpoint-renew")
                    .doesNotContain("/renew")
                    .doesNotContain("/heartbeat");
        }
    }

    @Test
    void readinessTestsMustNotCallExternalAnchorOrTrustAuthority() throws Exception {
        List<Path> readinessTests;
        try (Stream<Path> stream = Files.walk(Path.of("src/test/java/com/frauddetection/alert"))) {
            readinessTests = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.contains("ProductionReadiness")
                                || name.contains("RestartRecoveryProof")
                                || name.contains("RecoveryControllerTest");
                    })
                    .toList();
        }

        for (Path test : readinessTests) {
            String source = Files.readString(test);
            assertThat(source)
                    .as("Readiness proof tests must not depend on external finality: " + test)
                    .doesNotContain("ExternalAuditAnchorPublisher")
                    .doesNotContain("AuditTrustAuthorityClient")
                    .doesNotContain("HttpAuditTrustAuthorityClient")
                    .doesNotContain("TrustAuthority");
        }
    }

    @Test
    void readinessDocsMustPreserveProofOnlyLowCardinalityClaims() throws Exception {
        String combined = combinedReadinessDocs();

        assertContainsRequiredReadinessWording(combined);
        assertNoForbiddenProcessKillOverclaim(combined);
        assertNoProductionEnablementOverclaim(combined);
        assertThat(combined).contains("Do not include command id, alert id, actor id, lease owner, idempotency key, request hash, resource id");
    }

    @Test
    void readinessInspectionResponseMustNotExposeUnsafeFields() throws Exception {
        String response = readSource("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationCommandInspectionResponse.java");
        String openApi = readSource("../docs/openapi/alert_service.openapi.yaml");

        assertInspectionDtoNoUnsafeFields(response);
        assertInspectionSchemaNoUnsafeFields(openApi);
    }

    @Test
    void readinessCiMustBlockReadinessAndRegressionJobs() throws Exception {
        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));

        assertCiContainsRequiredReadinessJobs(ci);
    }

    @Test
    void readinessDocsMustNotUsePlaceholderOutput() throws Exception {
        assertThat(combinedReadinessDocs())
                .doesNotContain("TBD")
                .doesNotContain("TODO")
                .doesNotContain("Tests run: X")
                .doesNotContain("Failures: X")
                .doesNotContain("paste fake")
                .doesNotContain("Paste CI output here");
    }

    @Test
    void readinessDashboardThresholdsMustUseConcreteValues() throws Exception {
        String thresholds = readDoc("observability/regulated_mutation_alert_thresholds.md");
        String dashboard = readDoc("observability/regulated_mutation_dashboard.md");

        assertDocsHaveConcreteThresholds(thresholds);
        assertThat(dashboard)
                .contains("Threshold overlays")
                .contains("regulated_mutation_alert_thresholds.md");
    }

    @Test
    void readinessProofMatrixMustMapExactTestMethods() throws Exception {
        String matrix = readDoc("testing/regulated_mutation_readiness_proof.md");

        assertThat(matrix)
                .contains("The suite must prove readiness, not claim enablement.")
                .contains("| Invariant | Test class | Test method | Type | CI job | Failure meaning | Allowed production claim | Forbidden production claim |")
                .contains("EvidenceGatedFinalizeCoordinatorIntegrationTest` | `shouldFinalizeSubmitDecisionThroughRealMongoCoordinatorPath")
                .contains("EvidenceGatedFinalizeCoordinatorTest` | `shouldNotReplayStaleCommittedSnapshotWhenFinalizeRecoveryRequired")
                .contains("RegulatedMutationLeaseFencingIntegrationTest` | `expiredLeaseCanBeTakenOverAndStaleWorkerCannotWriteAfterTakeover")
                .contains("RegulatedMutationLeaseRenewalIntegrationTest` | `concurrentRenewalAtLastAllowedSlotAllowsOnlyOneSuccess")
                .contains("RegulatedMutationStaleWorkerExecutorIntegrationTest` | `evidenceGatedCheckpointBudgetExceededStopsBeforeFinalizeMutationThroughRealMongoExecutorPath")
                .contains("RegulatedMutationRestartRecoveryProofTest` | `crashAfterCanonicalLocalCommitBeforeExternalConfirmationDoesNotClaimConfirmedFinality")
                .contains("AlertControllerTest` | `recoveryRequiredWithSnapshot_mustNotReplayCommittedSuccess")
                .contains("AlertControllerTest` | `finalizeRecoveryRequiredWithSnapshot_mustNotReplayFinalizedSuccess")
                .contains("AlertControllerTest` | `renewalBudgetExceededRecovery_mustReturnExplicitRecovery")
                .contains("AlertControllerTest` | `staleCheckpointFailure_mustNotReturnSuccess")
                .contains("AlertControllerTest` | `longRunningProcessing_mustBeObservableNotSuccess")
                .contains("AlertControllerTest` | `expiredProcessingWithoutProof_mustNotReturnCommittedSuccess")
                .contains("RegulatedMutationRecoveryControllerTest` | `inspectionEndpointNeverReturnsRawSensitiveFieldsOrExceptionText")
                .contains("RegulatedMutationRollbackReadinessTest` | `disablingCheckpointRenewal_doesNotDisableFencing")
                .contains("RegulatedMutationRollbackReadinessTest` | `shrinkingRenewalBudget_doesNotCreateFalseSuccess")
                .contains("RegulatedMutationRollbackReadinessTest` | `rollbackKeepsRecoveryCommandsVisible")
                .contains("RegulatedMutationRollbackReadinessTest` | `currentRuntimeDoesNotStartAutonomousMutationSchedulers")
                .contains("RegulatedMutationRollbackReadinessTest` | `rollbackApiSmoke_doesNotHideRecovery");
        assertThat(matrix)
                .doesNotContain("recovery/finalize replay tests")
                .doesNotContain("rollback failure tests")
                .doesNotContain("local audit tests")
                .doesNotContain("heartbeat/renewal guards")
                .doesNotContain("recovery API tests")
                .doesNotContain("rollback tests/docs");
    }

    private List<String> enumValues(String source) {
        int open = source.indexOf('{');
        int close = source.lastIndexOf('}');
        return Stream.of(source.substring(open + 1, close).split(","))
                .map(value -> value.replaceAll("//.*", "").trim())
                .filter(value -> !value.isBlank())
                .map(value -> value.replaceAll("[;\\s].*", ""))
                .toList();
    }

    private String readSource(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private String readDoc(String relativePath) throws Exception {
        return Files.readString(Path.of("../docs/" + relativePath));
    }

    private String combinedReadinessDocs() throws Exception {
        return readDoc("testing/regulated_mutation_readiness_proof.md")
                + readDoc("testing/regulated_mutation_readiness_proof.md")
                + readDoc("testing/regulated_mutation_readiness_proof.md")
                + readDoc("observability/regulated_mutation_dashboard.md")
                + readDoc("observability/regulated_mutation_alert_thresholds.md")
                + readDoc("runbooks/regulated_mutation_drills.md")
                + readDoc("runbooks/regulated_mutation_drills.md")
                + readDoc("operations/regulated_mutation_rollback_plan.md");
    }

    private void assertNoForbiddenTerms(String source, String category, String... forbiddenClaims) {
        String normalized = source.toLowerCase();
        for (String forbiddenClaim : forbiddenClaims) {
            assertThat(normalized)
                    .as("forbidden readiness " + category + " term must be absent: " + forbiddenClaim)
                    .doesNotContain(forbiddenClaim.toLowerCase());
        }
    }

    private void assertNoForbiddenProcessKillOverclaim(String source) {
        // Modeled restart proof must not drift into real process/container kill claims.
        assertNoForbiddenTerms(
                source,
                "process-kill overclaim",
                "Real process-kill chaos proven.",
                "real process-kill proof",
                "OS kill chaos proven",
                "JVM kill chaos proven",
                "container kill proof",
                "kill -9 chaos proof"
        );
    }

    private void assertNoProductionEnablementOverclaim(String source) {
        // Readiness evidence does not enable production or bank operation.
        assertNoForbiddenTerms(
                source,
                "production enablement overclaim",
                "Production enabled.",
                "Bank certified.",
                "production certified",
                "bank enabled",
                "External finality proven.",
                "Distributed ACID achieved.",
                "fully production ready",
                "external finality guaranteed"
        );
    }

    private void assertContainsRequiredReadinessWording(String source) {
        assertThat(source)
                .contains("modeled restart and recovery readiness")
                .contains("CI provides modeled restart/recovery proof. It verifies durable post-crash command states, replay policy")
                .contains("True OS/JVM/container termination chaos remains future scope unless explicitly implemented.")
                .contains("No new public API statuses")
                .contains("Checkpoint renewal must not be treated as business progress")
                .contains("The suite must prove readiness, not claim enablement.")
                .contains("Modeled restart/recovery, controller recovery behavior, Docker/Testcontainers readiness, rollback, dashboard, alert, and operator drill evidence are covered.");
    }

    private void assertInspectionDtoNoUnsafeFields(String source) {
        // Inspection response may expose bounded booleans/hashes, never raw identifiers or raw errors.
        assertThat(source)
                .contains("@JsonProperty(\"resource_id_present\")")
                .contains("@JsonProperty(\"resource_id_hash\")")
                .contains("@JsonProperty(\"lease_owner_present\")")
                .contains("@JsonProperty(\"lease_owner_hash\")")
                .contains("@JsonProperty(\"last_error_code\")")
                .contains("safeResourceIdHash")
                .contains("safeLeaseOwnerHash")
                .contains("safeErrorCode")
                .doesNotContain("@JsonProperty(\"resource_id\")")
                .doesNotContain("@JsonProperty(\"lease_owner\")")
                .doesNotContain("@JsonProperty(\"last_error\")")
                .doesNotContain("@JsonProperty(\"idempotency_key\")")
                .doesNotContain("@JsonProperty(\"request_hash\")")
                .doesNotContain("@JsonProperty(\"intent_hash\")")
                .doesNotContain("@JsonProperty(\"payload_hash\")");
    }

    private void assertInspectionSchemaNoUnsafeFields(String source) {
        String schema = source.substring(source.indexOf("RegulatedMutationCommandInspectionResponse:"));
        schema = schema.substring(0, schema.indexOf("AuditDegradationListResponse:"));
        assertThat(schema)
                .contains("resource_id_present:")
                .contains("resource_id_hash:")
                .contains("lease_owner_present:")
                .contains("lease_owner_hash:")
                .contains("last_error_code:")
                .doesNotContain("resource_id:")
                .doesNotContain("lease_owner:")
                .doesNotContain("last_error:");
    }

    private void assertCiContainsRequiredReadinessJobs(String source) {
        // Both jobs must block Docker build and publish reports for CI triage.
        assertThat(source)
                .contains("fdp35-production-readiness")
                .contains("regulated-mutation-regression")
                .contains("docker version")
                .contains("-Dgroups=production-readiness,e2e,recovery-proof,integration")
                .contains("EvidenceGatedFinalizeCoordinatorIntegrationTest")
                .contains("RegulatedMutationLeaseFencingIntegrationTest")
                .contains("RegulatedMutationLeaseRenewalIntegrationTest")
                .contains("RegulatedMutationCheckpointRenewalExecutionTest")
                .contains("RegulatedMutationStaleWorkerExecutorIntegrationTest")
                .contains("RegulatedMutationRecoveryControllerTest")
                .contains("RegulatedMutationRollbackReadinessTest")
                .contains("RegulatedMutationArchitectureTest")
                .contains("- regulated-mutation-regression")
                .contains("alert-service/target/surefire-reports/")
                .contains("alert-service/target/failsafe-reports/");
    }

    private void assertDocsHaveConcreteThresholds(String source) {
        assertThat(source)
                .contains("P1")
                .contains("P2")
                .contains("> 0")
                .contains(">= 3")
                .contains("5 minutes")
                .contains("10 minutes")
                .contains("15 minutes")
                .contains("30 minutes")
                .contains("warning")
                .contains("critical");
    }

    @Test
    void leaseRenewalModelPolicyMustOwnModelStateTables() throws Exception {
        String policy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalPolicy.java"
        ));
        String evidence = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedLeaseRenewalModelPolicy.java"
        ));

        assertThat(policy).contains("RegulatedMutationLeaseRenewalModelPolicy");
        assertThat(policy).contains("Duplicate regulated mutation lease renewal model policy");
        assertThat(policy).contains("Missing regulated mutation lease renewal model policy");
        assertThat(policy).doesNotContain("LEGACY_RENEWABLE_STATES");
        assertThat(policy).doesNotContain("EVIDENCE_GATED_RENEWABLE_STATES");
        assertThat(evidence).contains("RegulatedMutationState.FINALIZING");
        assertThat(evidence).contains("RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED");
    }

    @Test
    void executorsMustNotRenewLeasesDirectlyOrUpdateLeaseExpiryWithMongo() throws Exception {
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(evidenceExecutor)
                .doesNotContain("RegulatedMutationLeaseRenewalService")
                .doesNotContain(".set(\"lease_expires_at\"");
    }

    @Test
    void checkpointRenewalMustNotBeSchedulerPublicApiOrBoundaryLeak() throws Exception {
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
                .contains("beforeEvidencePreparation")
                .contains("afterEvidencePreparedBeforeFinalize")
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
        assertThat(policy).contains("BEFORE_EVIDENCE_PREPARATION");
        assertThat(policy).contains("AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE");
        assertThat(policy).doesNotContain("AFTER_ATTEMPTED_AUDIT");

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
    void executorsMustUseNamedCheckpointMethodsInsteadOfDirectRenewal() throws Exception {
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(evidenceExecutor)
                .contains("checkpointRenewalService.beforeEvidencePreparation")
                .contains("checkpointRenewalService.afterEvidencePreparedBeforeFinalize")
                .contains("checkpointRenewalService.beforeEvidenceGatedFinalize")
                .doesNotContain("leaseRenewalService.renew")
                .doesNotContain("RegulatedMutationLeaseRenewalService");
    }

    @Test
    void everyCheckpointMustHavePolicyDocsRunbookAndMetricCoverage() throws Exception {
        String policyTest = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/RegulatedMutationSafeCheckpointPolicyTest.java"
        ));
        String architecture = Files.readString(Path.of("../docs/architecture/regulated_mutation_safe_checkpoint_policy.md"));
        String runbook = Files.readString(Path.of("../docs/runbooks/regulated_mutation_recovery.md"));
        String metricsTest = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/observability/AlertServiceMetricsTest.java"
        ));

        for (RegulatedMutationRenewalCheckpoint checkpoint : RegulatedMutationRenewalCheckpoint.values()) {
            assertThat(policyTest).as("policy test coverage for " + checkpoint).contains(checkpoint.name());
            assertThat(architecture).as("architecture docs coverage for " + checkpoint).contains(checkpoint.name());
            assertThat(runbook).as("runbook coverage for " + checkpoint).contains(checkpoint.name());
            assertThat(metricsTest).as("metrics label coverage for " + checkpoint).contains(checkpoint.name());
        }
    }

    @Test
    void safeCheckpointPolicyMustRemainExplicitWithoutWildcardAllows() throws Exception {
        String policy = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationSafeCheckpointPolicy.java"
        ));

        assertThat(policy).contains("new EnumMap<>(RegulatedMutationState.class)");
        assertThat(policy).doesNotContain("LEGACY_REGULATED_MUTATION");
        assertThat(policy).contains("evidence.put(RegulatedMutationState.EVIDENCE_PREPARING");
        assertThat(policy).contains("evidence.put(RegulatedMutationState.FINALIZING");
        assertThat(policy)
                .doesNotContain("RegulatedMutationState.values()")
                .doesNotContain("RegulatedMutationRenewalCheckpoint.values()")
                .doesNotContain("return true;")
                .doesNotContain("Set.copyOf(EnumSet.allOf");
    }

    @Test
    void checkpointRenewalServiceMustRemainNarrowOwnershipAdapter() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationCheckpointRenewalService.java"
        ));

        assertThat(service).contains("leaseRenewalService.renew");
        assertThat(service)
                .doesNotContain("command.mutation().execute")
                .doesNotContain("auditPhaseService")
                .doesNotContain("commandRepository.save")
                .doesNotContain("TransactionalOutboxRecordRepository")
                .doesNotContain("AlertRepository")
                .doesNotContain("KafkaTemplate")
                .doesNotContain("FraudDecisionEventPublisher")
                .doesNotContain("ExternalAudit")
                .doesNotContain("TrustAuthority")
                .doesNotContain("Anchor");
    }

    @Test
    void disabledCheckpointRenewalMustStayOutOfProductionBeanGraph() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/RegulatedMutationCheckpointRenewalService.java"
        ));
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));
        String wiringTest = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/RegulatedMutationCheckpointRenewalWiringTest.java"
        ));

        assertThat(service).contains("boolean isEnabledForTesting()");
        assertThat(evidenceExecutor).contains("Production wiring must use Spring-managed checkpoint renewal service");
        assertThat(wiringTest).contains("productionExecutorUsesEnabledSpringManagedCheckpointRenewalService");
        assertThat(wiringTest).contains("isEnabledForTesting()).isTrue()");
        assertThat(evidenceExecutor.substring(evidenceExecutor.indexOf("@Autowired")))
                .doesNotContain("RegulatedMutationCheckpointRenewalService.disabledForTesting()");
    }

    @Test
    void productionWiringMustNotSilentlyDisableCheckpointRenewal() throws Exception {
        String evidenceExecutor = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"
        ));

        assertThat(evidenceExecutor).contains("Objects.requireNonNull");
        assertThat(evidenceExecutor).contains("Test-support constructor only");
        assertThat(evidenceExecutor).doesNotContain("checkpointRenewalService == null");
    }

    @Test
    void checkpointRenewalDocsMustDescribeAdoptionWithoutReviewNotes() throws Exception {
        String architecture = Files.readString(Path.of("../docs/architecture/regulated_mutation_safe_checkpoint_policy.md"));
        String checkpoints = Files.readString(Path.of("../docs/architecture/regulated_mutation_safe_checkpoints.md"));
        String runbook = Files.readString(Path.of("../docs/runbooks/regulated_mutation_recovery.md"));
        String adoption = Files.readString(Path.of("../docs/architecture/regulated_mutation_checkpoint_adoption.md"));
        String combined = architecture + "\n" + checkpoints + "\n" + runbook + "\n" + adoption;

        assertThat(combined).contains("Renewal preserves ownership, not progress");
        assertThat(combined).contains("Checkpoint renewal preserves bounded lease ownership. It does not prove business progress");
        assertThat(combined).contains("No generic heartbeat system");
        assertThat(combined).contains("No automatic infinite renewal loop");
        assertThat(combined).contains("Checkpoint renewal failure stops execution");
        assertThat(combined).contains("BEFORE_EVIDENCE_PREPARATION");
        assertThat(combined).contains("AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE");
        assertThat(combined).contains("BEFORE_EVIDENCE_GATED_FINALIZE");
        assertThat(combined).contains("not emitted for a normal successful checkpoint renewal");
        assertThat(combined).contains("Production executors require");
        assertThat(combined).contains("checkpoint-renewal extension");
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
    void leaseFencingDocsMustDescribeProtectionWithoutReviewNotes() throws Exception {
        String combined = Files.readString(Path.of("../docs/architecture/regulated_mutation_lease_fencing.md"));

        assertThat(combined).contains("claim acquisition is not write fencing");
        assertThat(combined).contains("post-claim transitions are fenced");
        assertThat(combined).containsIgnoringCase("command transition fencing is not business-side-effect rollback by itself");
        assertThat(combined).contains("transaction-mode `REQUIRED`");
        assertThat(combined).contains("current runtime requires");
        assertThat(combined).contains("stale worker");
        assertThat(combined).contains("no silent `repository.save` after claim");
        assertThat(combined).contains("does not expand transaction scope");
        assertThat(combined).contains("no distributed lock");
        assertThat(combined).contains("Source-string architecture tests are guardrails, not complete architectural proof");
        assertThat(combined).contains("lease-owner fenced command transition design is the current runtime contract");
        assertThat(combined).doesNotContain("Merge Decision");
        assertThat(combined).doesNotContain("GO:");
        assertThat(combined).doesNotContain("NO-GO:");
        assertThat(combined).doesNotContain("reviewer");
    }

    @Test
    void leaseRenewalDocsMustDescribeBoundedRenewalWithoutReviewNotes() throws Exception {
        String runbook = Files.readString(Path.of("../docs/operations/regulated_mutation_lease_renewal.md"));
        String operatorRunbook = Files.readString(Path.of("../docs/runbooks/regulated_mutation_recovery.md"));
        String dashboard = Files.readString(Path.of("../docs/observability/regulated_mutation_lease_renewal_dashboard.md"));
        String combined = runbook + "\n" + operatorRunbook + "\n" + dashboard;

        assertThat(combined).contains("owner-fenced");
        assertThat(combined).contains("bounded");
        assertThat(combined).contains("lease_expires_at > now");
        assertThat(combined).contains("Renewal Caller Contract");
        assertThat(combined).contains("Runtime Adoption Contract");
        assertThat(combined).contains("Lease renewal is not a guarantee of progress");
        assertThat(combined).contains("EvidenceGatedFinalizeExecutor");
        assertThat(combined).contains("BEFORE_EVIDENCE_PREPARATION");
        assertThat(combined).contains("AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE");
        assertThat(combined).contains("BEFORE_EVIDENCE_GATED_FINALIZE");
        assertThat(combined).contains("Missing renewal metadata is backward compatible");
        assertThat(combined).contains("Missing, null, retired, unknown, or mismatched model versions fail closed");
        assertThat(combined).contains("EVIDENCE_GATED_FINALIZE_V1");
        assertThat(combined).contains("LEASE_RENEWAL_BUDGET_EXCEEDED");
        assertThat(combined).contains("Recovery status wins over `responseSnapshot`");
        assertThat(combined).contains("must not be used as idle queue parking");
        assertThat(combined).contains("`execution_status` and recovery precedence remain authoritative");
        assertThat(combined).contains("EVIDENCE_PREPARING");
        assertThat(combined).contains("FINALIZING");
        assertThat(combined).contains("FINALIZED_VISIBLE");
        assertThat(combined).contains("FINALIZE_RECOVERY_REQUIRED");
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
        assertThat(combined).containsIgnoringCase("do not increase budget blindly");
        assertThat(combined).containsIgnoringCase("do not bypass fencing");
        assertThat(combined).contains("does not enable production or bank behavior by itself");
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
        assertThat(combined).doesNotContain("LEGACY_REGULATED_MUTATION");
        assertThat(combined).doesNotContain("BEFORE_LEGACY_BUSINESS_COMMIT");
        assertThat(combined).doesNotContain("BEFORE_SUCCESS_AUDIT_RETRY");
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
    void localAuditPhaseWriterMustOnlyBeUsedByCurrentFinalizePath() throws Exception {
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
                    .as("RegulatedMutationLocalAuditPhaseWriter must not leak outside current finalize path: " + path)
                    .doesNotContain("RegulatedMutationLocalAuditPhaseWriter");
        }
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
        assertThat(writer).contains("Transactional outbox repository is required");
    }

    @Test
    void docsMustNotOverclaimLocalAuditEvidence() throws Exception {
        String readme = Files.readString(Path.of("../README.md"));
        String api = Files.readString(Path.of("../docs/api/api_surface_v1.md"));
        String security = Files.readString(Path.of("../docs/security/security_architecture.md"));
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
    void currentFinalizeDocsMustDescribeLocalScopeAndTargetGaps() throws Exception {
        String readme = Files.readString(Path.of("../README.md"));
        String adr = Files.readString(Path.of("../docs/adr/evidence_gated_regulated_mutation_finalize.md"));
        String handoff = Files.readString(Path.of("../docs/architecture/regulated_mutation_runtime_handoff.md"));
        String preconditions = Files.readString(Path.of("../docs/architecture/evidence_gated_finalize_preconditions.md"));
        String openApi = Files.readString(Path.of("../docs/openapi/alert_service.openapi.yaml"));
        String combined = readme + "\n" + adr + "\n" + handoff + "\n" + preconditions + "\n" + openApi;

        assertThat(combined).containsIgnoringCase("local evidence-precondition-gated finalize");
        assertThat(combined).contains("Persisted Compatibility Cut");
        assertThat(combined).contains("EVIDENCE_GATED_FINALIZE_V1");
        assertThat(combined).contains("External anchor readiness");
        assertThat(combined).contains("Trust Authority signing readiness");
        assertThat(combined).contains("not part of the current local finalize transaction");
        assertThat(combined).contains("not distributed ACID");
    }

    @Test
    void currentFinalizeDocsMustNotContainPromptDecisionNotes() throws Exception {
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

    @Test
    void realChaosHarnessMustStayOutsideRuntimeSource() throws Exception {
        assertThat(Files.exists(Path.of("src/main/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationDockerChaosHarness.java")))
                .isFalse();
        assertThat(Files.exists(Path.of("src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationDockerChaosHarness.java")))
                .isFalse();
        assertThat(Files.exists(Path.of("src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationAlertServiceProcessChaosHarness.java")))
                .isTrue();
        String harness = Files.readString(Path.of("src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationAlertServiceProcessChaosHarness.java"));
        assertThat(harness)
                .contains("ALERT_SERVICE_MAIN_CLASS")
                .contains("com.frauddetection.alert.AlertServiceApplication")
                .contains("ALERT_SERVICE_TARGET_NAME")
                .contains("alert-service")
                .contains("destroyForcibly")
                .contains("REAL_ALERT_SERVICE_KILL")
                .contains("/api/v1/regulated-mutations/recover")
                .contains("/api/v1/regulated-mutations/by-command/")
                .doesNotContain("DockerImageName.parse(\"alpine:3.20\")")
                .doesNotContain("while true; do sleep 1; done");

        List<Path> runtimeFiles;
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            runtimeFiles = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path path : runtimeFiles) {
            String source = Files.readString(path);
            assertThat(source)
                    .as("FDP-36 chaos harness must not leak into runtime source: " + path)
                    .doesNotContain("RegulatedMutationDockerChaosHarness")
                    .doesNotContain("RegulatedMutationAlertServiceProcessChaosHarness")
                    .doesNotContain("RegulatedMutationChaosScenario")
                    .doesNotContain("RegulatedMutationProofLevel")
                    .doesNotContain("Process.destroyForcibly")
                    .doesNotContain("service-chaos")
                    .doesNotContain("docker-chaos")
                    .doesNotContain("real-chaos")
                    .doesNotContain("in-flight-chaos")
                    .doesNotContain("killContainer")
                    .doesNotContain("stopContainer")
                    .doesNotContain("process kill")
                    .doesNotContain("test chaos hook");
        }
    }

    @Test
    void realChaosMustNotLeakIntoExecutorsCoordinatorsOrPolicies() throws Exception {
        List<Path> protectedRuntimeFiles = List.of(
                Path.of("src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationClaimService.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationReplayResolver.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationFencedCommandWriter.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalPolicy.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationSafeCheckpointPolicy.java")
        );

        for (Path path : protectedRuntimeFiles) {
            String source = Files.readString(path);
            assertThat(source)
                    .as("Runtime regulated mutation path must not contain FDP-36 chaos code: " + path)
                    .doesNotContain("chaos")
                    .doesNotContain("killContainer")
                    .doesNotContain("stopContainer")
                    .doesNotContain("DockerClientFactory")
                    .doesNotContain("@Tag(\"real-chaos\")")
                    .doesNotContain("@Tag(\"docker-chaos\")");
        }
    }

    @Test
    void realChaosDocsMustAvoidEnablementOverclaims() throws Exception {
        String adr = Files.readString(Path.of("../docs/adr/fdp_36_real_chaos_enable_readiness.md"));
        String mergeGate = Files.readString(Path.of("../docs/fdp/fdp_36_merge_gate.md"));
        String checklist = Files.readString(Path.of("../docs/fdp/fdp_36_enablement_decision_checklist.md"));
        String matrix = Files.readString(Path.of("../docs/testing/fdp_36_real_chaos_proof.md"));
        String runbook = Files.readString(Path.of("../docs/runbooks/regulated_mutation_drills.md"));
        String combined = adr + "\n" + mergeGate + "\n" + checklist + "\n" + matrix + "\n" + runbook;

        assertThat(combined).contains("FDP-36 provides real alert-service JVM/process kill-restart proof over selected durable crash-window states. It does not change regulated mutation semantics.");
        assertThat(combined).contains("FDP-36 kills and restarts the real alert-service JVM/process. Most crash windows are durable-state crash-window proofs, not live in-flight instruction-boundary kills.");
        assertThat(combined).contains("Docker/Testcontainers are infrastructure dependencies, not the killed alert-service image.");
        assertThat(combined).contains("The real-chaos proof kills the actual alert-service JVM/process running regulated mutation execution, recovery, and inspection endpoints.");
        assertThat(combined).contains("REAL_ALERT_SERVICE_KILL");
        assertThat(combined).contains("REAL_ALERT_SERVICE_RESTART_API_PROOF");
        assertThat(combined).contains("LIVE_IN_FLIGHT_REQUEST_KILL");
        assertThat(combined).contains("MODELED_DURABLE_STATE_PROOF");
        assertThat(combined).contains("Proof Level vs Non-Claimed Chaos Level");
        assertThat(combined).contains("Documentation must not call dummy-container proof real service chaos.");
        assertThat(combined).contains("FDP-35 provides modeled restart/recovery proof");
        assertThat(combined).contains("FDP-36 proof code is test-only. No runtime hooks are introduced.");
        assertThat(combined).contains("Full alert-service image container chaos is future scope");
        assertThat(combined).contains("READY_FOR_ENABLEMENT_REVIEW is not production enablement.");
        assertThat(combined).contains("no runtime chaos hooks in executors, coordinators, or domain services");
        assertThat(combined).contains("no alternate regulated mutation runtime or fallback");
        assertRealChaosForbiddenPhraseIsContextual(combined, "production enabled");
        assertRealChaosForbiddenPhraseIsContextual(combined, "production certified");
        assertRealChaosForbiddenPhraseIsContextual(combined, "external finality");
        assertRealChaosForbiddenPhraseIsContextual(combined, "distributed ACID");
        assertRealChaosForbiddenPhraseIsContextual(combined, "distributed lock");
        assertRealChaosForbiddenPhraseIsContextual(combined, "exactly-once Kafka");
        assertRealChaosForbiddenPhraseIsContextual(combined, "WORM");
        assertRealChaosForbiddenPhraseIsContextual(combined, "legal notarization");
        assertRealChaosForbiddenPhraseIsContextual(combined, "KMS/HSM");
        assertRealChaosForbiddenPhraseIsContextual(combined, "automatic bank enablement");
        assertRealChaosForbiddenPhraseIsContextual(combined, "alternate model enablement");
    }

    @Test
    void realChaosProofMatrixRowsMustMapToConcreteTestsAndCiJobs() throws Exception {
        String matrix = Files.readString(Path.of("../docs/testing/fdp_36_real_chaos_proof.md"));

        assertThat(matrix).contains("Proof Level");
        assertThat(matrix).contains("State Reach Method");
        assertThat(matrix).contains("Runtime In-Flight Kill?");
        assertThat(matrix).contains("Exact Claim");
        assertThat(matrix).contains("durable-state-seeded while real alert-service is running");
        assertThat(matrix).contains("real alert-service JVM restart proof over selected durable crash-window state");
        assertThat(matrix).contains("No FDP-36 row currently claims full live in-flight instruction-boundary kill.");
        assertThat(matrix).contains("RegulatedMutationRealAlertServiceChaosIT");
        assertThat(matrix).contains("RegulatedMutationRealAlertServiceEvidenceIntegrityIT");
        assertThat(matrix).contains("RegulatedMutationLiveInFlightKillIT");
        assertThat(matrix).contains("RegulatedMutationPostRestartApiBehaviorTest");
        assertThat(matrix).contains("fdp36-real-chaos");
        assertThat(matrix).contains("REAL_ALERT_SERVICE_KILL");
        assertThat(matrix).contains("REAL_ALERT_SERVICE_RESTART_API_PROOF");
        assertThat(matrix).contains("LIVE_IN_FLIGHT_REQUEST_KILL");
        assertThat(matrix.lines()
                .filter(line -> line.contains("REAL_ALERT_SERVICE_KILL"))
                .toList())
                .allSatisfy(line -> assertThat(line).contains("actual alert-service"));
        assertThat(matrix).doesNotContain("NOT_COVERED_IN_FDP36");
        assertThat(matrix.lines()
                .filter(line -> line.startsWith("| "))
                .filter(line -> !line.contains("---"))
                .filter(line -> !line.contains("Crash window"))
                .filter(line -> !line.contains("Invariant |"))
                .toList())
                .allSatisfy(line -> assertThat(line)
                        .contains("`")
                        .doesNotContain("|  |"));
    }

    @Test
    void realChaosCiMustContainRequiredJobAndArtifacts() throws Exception {
        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        String verifier = Files.readString(Path.of("../scripts/ci/verify-fdp36-artifacts.mjs"));

        assertThat(ci).contains("fdp36-real-chaos:");
        assertThat(ci).contains("Run FDP-36 real alert-service kill suite");
        assertThat(ci).contains("docker version");
        assertThat(ci).contains("-Dgroups=real-chaos,docker-chaos,service-chaos,integration");
        assertThat(ci).contains("-Dtest=RegulatedMutationRealAlertServiceChaosIT,RegulatedMutationRealAlertServiceEvidenceIntegrityIT");
        assertThat(ci).contains("RegulatedMutationLiveInFlightKillIT");
        assertThat(ci).contains("node scripts/ci/verify-fdp36-artifacts.mjs");
        assertThat(verifier).contains("killed target: actual alert-service JVM/process");
        assertThat(verifier).contains("Docker/Testcontainers are infrastructure dependencies, not the killed alert-service image.");
        assertThat(verifier).contains("FDP-36 real chaos is not sufficient without regulated-mutation-regression.");
        assertThat(verifier).contains("fdp36-proof-summary.md");
        assertThat(verifier).contains("REAL_ALERT_SERVICE_KILL, REAL_ALERT_SERVICE_RESTART_API_PROOF, LIVE_IN_FLIGHT_REQUEST_KILL");
        assertThat(ci).contains("alert-service/target/fdp36-chaos/");
        assertThat(ci).contains("fdp36-real-chaos-test-reports");
        assertThat(ci).contains("if-no-files-found: ignore");
        assertThat(ci).contains("regulated-mutation-regression");
        assertThat(ci).contains("RegulatedMutationPostRestartApiBehaviorTest");
    }

    @Test
    void productionImageChaosMustStayOutsideRuntimeSource() throws Exception {
        assertThat(Files.exists(Path.of("src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationProductionImageChaosHarness.java")))
                .isTrue();
        assertThat(Files.exists(Path.of("src/main/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationProductionImageChaosHarness.java")))
                .isFalse();

        String harness = Files.readString(Path.of("src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationProductionImageChaosHarness.java"));
        assertThat(harness)
                .contains("PRODUCTION_IMAGE_CONTAINER_KILL")
                .contains("DockerImageName.parse(imageName)")
                .contains("killContainerCmd")
                .contains("target\", \"fdp37-chaos")
                .contains("IMAGE_DIGEST_PROPERTY")
                .contains("IMAGE_ID_PROPERTY")
                .contains("network_mode")
                .contains("testcontainers-shared-network")
                .contains("host_networking_used")
                .contains("fdp37-enablement-review-pack.md")
                .contains("waitUntil(")
                .contains("live_in_flight_proof_executed")
                .contains("fdp37-rollback-validation.md")
                .contains("FDP-37 killed target must be the alert-service image/container")
                .doesNotContain("withNetworkMode(\"host\")")
                .doesNotContain("network_mode=host")
                .doesNotContain("DockerImageName.parse(\"alpine")
                .doesNotContain("while true; do sleep");

        List<Path> runtimeFiles;
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            runtimeFiles = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path path : runtimeFiles) {
            String source = Files.readString(path);
            assertThat(source)
                    .as("FDP-37 production-image chaos must not leak into runtime source: " + path)
                    .doesNotContain("RegulatedMutationProductionImageChaosHarness")
                    .doesNotContain("PRODUCTION_IMAGE_CONTAINER_KILL")
                    .doesNotContain("production-image-chaos")
                    .doesNotContain("DockerImageName")
                    .doesNotContain("GenericContainer")
                    .doesNotContain("killContainerCmd")
                    .doesNotContain("fdp37-chaos")
                    .doesNotContain("test-only checkpoint");
        }
    }

    @Test
    void productionImageChaosMustNotLeakIntoExecutorsCoordinatorsOrPolicies() throws Exception {
        List<Path> protectedRuntimeFiles = List.of(
                Path.of("src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationClaimService.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationReplayResolver.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationFencedCommandWriter.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationLeaseRenewalPolicy.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/RegulatedMutationSafeCheckpointPolicy.java")
        );

        for (Path path : protectedRuntimeFiles) {
            String source = Files.readString(path);
            assertThat(source)
                    .as("Runtime regulated mutation path must not contain FDP-37 production-image chaos code: " + path)
                    .doesNotContain("production-image-chaos")
                    .doesNotContain("PRODUCTION_IMAGE_CONTAINER_KILL")
                    .doesNotContain("DockerImageName")
                    .doesNotContain("GenericContainer")
                    .doesNotContain("killContainerCmd")
                    .doesNotContain("fdp37-chaos");
        }
    }

    @Test
    void productionImageLiveInFlightCheckpointSupportMustStayTestOnly() throws Exception {
        List<Path> runtimeFiles;
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            runtimeFiles = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path path : runtimeFiles) {
            String source = Files.readString(path);
            assertThat(source)
                    .as("Live in-flight checkpoint support must not leak into runtime source: " + path)
                    .doesNotContain("fdp36-live-in-flight")
                    .doesNotContain("app.fdp36.live-in-flight")
                    .doesNotContain("LiveInFlightMutationBlocker");
        }

        String fixture = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/chaos/LiveInFlightMutationBlockerConfiguration.java"
        ));
        String configParity = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/RegulatedMutationProductionImageConfigParityIT.java"
        ));
        String proofSummaryHarness = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationProductionImageChaosHarness.java"
        ));

        assertThat(fixture).contains("@Profile(\"fdp36-live-in-flight\")");
        assertThat(configParity).contains("noneMatch(argument -> argument.contains(\"fdp36-live-in-flight\"))");
        assertThat(proofSummaryHarness).contains("live_fixture_enabled");
    }

    @Test
    void liveRuntimeCheckpointSupportMustStayInTestFixtureOnly() throws Exception {
        assertThat(Files.exists(Path.of("src/test/java/com/frauddetection/alert/regulated/chaos/LiveRuntimeCheckpointBarrierConfiguration.java")))
                .isTrue();
        assertThat(Files.exists(Path.of("../deployment/Dockerfile.alert-service-fdp38-fixture")))
                .isTrue();

        List<Path> runtimeFiles;
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            runtimeFiles = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path path : runtimeFiles) {
            String source = Files.readString(path);
            assertThat(source)
                    .as("FDP-38 checkpoint barrier must not leak into runtime source: " + path)
                    .doesNotContain("FDP38")
                    .doesNotContain("fdp38-live-runtime-checkpoint")
                    .doesNotContain("LiveRuntimeCheckpoint")
                    .doesNotContain("ChaosBarrier")
                    .doesNotContain("LIVE_IN_FLIGHT_REQUEST_KILL")
                    .doesNotContain("RUNTIME_REACHED_TEST_FIXTURE");
        }

        List<Path> protectedRuntimeFiles = List.of(
                Path.of("src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator.java"),
                Path.of("src/main/java/com/frauddetection/alert/regulated/mutation/submitdecision/SubmitDecisionMutationHandler.java")
        );
        for (Path path : protectedRuntimeFiles) {
            String source = Files.readString(path);
            assertThat(source)
                    .as("Runtime mutation path must not branch on FDP-38 chaos: " + path)
                    .doesNotContain("fdp38")
                    .doesNotContain("checkpoint barrier")
                    .doesNotContain("LIVE_IN_FLIGHT_REQUEST_KILL")
                    .doesNotContain("RUNTIME_REACHED_TEST_FIXTURE");
        }

        String fixture = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/chaos/LiveRuntimeCheckpointBarrierConfiguration.java"
        ));
        String dockerfile = Files.readString(Path.of("../deployment/Dockerfile.alert-service-fdp38-fixture"));
        String productionImageConfigParity = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/RegulatedMutationProductionImageConfigParityIT.java"
        ));

        assertThat(fixture)
                .contains("@Profile(\"fdp38-live-runtime-checkpoint\")")
                .contains("fdp38_live_checkpoint_barriers")
                .contains("checkpoint_reached")
                .contains("BEFORE_EVIDENCE_PREPARATION")
                .contains("AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE")
                .contains("BEFORE_EVIDENCE_GATED_FINALIZE");
        assertThat(dockerfile)
                .contains("Dockerfile.alert-service-fdp38-fixture")
                .contains("target/test-classes")
                .contains("AlertServiceApplication");
        assertThat(Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationLiveCheckpointChaosHarness.java"
        )))
                .contains("contains_test_classes")
                .contains("contains_test_profiles")
                .contains("release_candidate_allowed")
                .contains("production_deployable")
                .contains("false_success_evaluation")
                .contains("failed_false_success_reasons")
                .contains("precondition_setup");
        assertThat(productionImageConfigParity)
                .contains("noneMatch(argument -> argument.contains(\"fdp38-live-runtime-checkpoint\"))");
    }

    @Test
    void liveRuntimeCheckpointDocsMustNotOverclaimFixtureProof() throws Exception {
        String adr = Files.readString(Path.of("../docs/adr/fdp_38_live_runtime_checkpoint_fixture_proof.md"));
        String mergeGate = Files.readString(Path.of("../docs/fdp/fdp_38_merge_gate.md"));
        String matrix = Files.readString(Path.of("../docs/testing/fdp_38_live_runtime_checkpoint_proof.md"));
        String proofPack = Files.readString(Path.of("../docs/testing/fdp_38_live_runtime_checkpoint_proof.md"));
        String combined = adr + "\n" + mergeGate + "\n" + matrix + "\n" + proofPack;

        assertThat(combined).contains("dedicated alert-service test-fixture image");
        assertThat(combined).contains("fixture image is not a production image");
        assertThat(combined).contains("FDP-38 is not production enablement");
        assertThat(combined).contains("LIVE_IN_FLIGHT_REQUEST_KILL");
        assertThat(combined).contains("RUNTIME_REACHED_TEST_FIXTURE");
        assertThat(combined).contains("FDP-38 does not claim `RUNTIME_REACHED_PRODUCTION_IMAGE`");
        assertThat(combined).contains("release_image: false");
        assertThat(combined).contains("contains_test_classes: true");
        assertThat(combined).contains("contains_test_profiles: true");
        assertThat(combined).contains("release_candidate_allowed: false");
        assertThat(combined).contains("production_deployable: false");
        assertThat(combined).contains("production_enablement: false");
        assertThat(combined).contains("LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST");
        assertThat(combined).contains("Every `LiveRuntimeCheckpoint` enum value must be represented");
        assertThat(combined).contains("false_success_evaluation");
        assertThat(combined).contains("failed_false_success_reasons: []");
        assertThat(combined).contains("The release image does not contain checkpoint barrier support");

        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "production certified");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "bank certified");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "final production image live checkpoint proof");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "all crash windows killed live");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "full instruction-boundary coverage");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "production enablement");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "external finality");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "distributed ACID");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "Kafka exactly-once");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "legal notarization");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "WORM guarantee");
        assertLiveCheckpointForbiddenPhraseIsContextual(combined, "production deployable");
    }

    @Test
    void liveRuntimeCheckpointCiMustRequireFixtureProof() throws Exception {
        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        String verifier = Files.readString(Path.of("../scripts/ci/verify-fdp38-artifacts.mjs"));
        String verifierHelpers = Files.readString(Path.of("../scripts/ci/artifact-verification-helpers.mjs"));
        String verifierSurface = verifier + "\n" + verifierHelpers;

        assertThat(ci).contains("fdp38-live-runtime-checkpoint-chaos:");
        assertThat(ci).contains("fdp38-alert-service-test-fixture:${GITHUB_SHA}");
        assertThat(ci).contains("deployment/Dockerfile.alert-service-fdp38-fixture");
        assertThat(ci).contains("-Dfdp38.live-runtime-checkpoint.enabled=true");
        assertThat(ci).contains("-Dfdp38.alert-service.fixture-image=${FDP38_ALERT_SERVICE_FIXTURE_IMAGE}");
        assertThat(ci).contains("RegulatedMutationLiveCheckpointBeforeEvidencePreparationIT");
        assertThat(ci).contains("RegulatedMutationLiveCheckpointAfterEvidencePreparedBeforeFinalizeIT");
        assertThat(ci).contains("RegulatedMutationLiveCheckpointBeforeEvidenceGatedFinalizeIT");
        assertThat(ci).contains("node scripts/ci/verify-fdp38-artifacts.mjs");
        assertThat(verifierSurface).contains("skipped > 0");
        assertThat(verifier).contains("LIVE_IN_FLIGHT_REQUEST_KILL");
        assertThat(verifier).contains("RUNTIME_REACHED_TEST_FIXTURE");
        assertThat(verifier).contains("checkpoint_reached=true");
        assertThat(verifier).contains("no_false_success=true");
        assertThat(verifier).contains("release_image=false");
        assertThat(verifier).contains("contains_test_classes");
        assertThat(verifier).contains("contains_test_profiles");
        assertThat(verifier).contains("release_candidate_allowed");
        assertThat(verifier).contains("production_deployable");
        assertThat(verifier).contains("false_success_evaluation");
        assertThat(verifier).contains("failed_false_success_reasons");
        assertThat(verifier).contains("precondition_setup");
        assertThat(verifier).contains("fdp38-proof-summary-${checkpoint}.md");
        assertThat(verifier).contains("fdp38-proof-summary-${checkpoint}.json");
        assertThat(verifier).contains("fdp38-fixture-image-provenance.json");
        assertThat(ci).contains("fdp38-live-runtime-checkpoint-chaos-reports");
    }

    @Test
    void liveRuntimeCheckpointRegistrationMustBeExplicitInDocsTestsAndCi() throws Exception {
        String matrix = Files.readString(Path.of("../docs/testing/fdp_38_live_runtime_checkpoint_proof.md"));
        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        String verifier = Files.readString(Path.of("../scripts/ci/verify-fdp38-artifacts.mjs"));
        String harness = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationLiveCheckpointChaosHarness.java"
        ));
        String enumSource = Files.readString(Path.of(
                "src/test/java/com/frauddetection/alert/regulated/chaos/LiveRuntimeCheckpoint.java"
        ));

        assertThat(enumSource).contains("LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST");
        assertThat(harness)
                .contains("public_success_status_absent")
                .contains("committed_snapshot_absent_when_not_allowed")
                .contains("finalized_status_absent_when_not_allowed")
                .contains("success_audit_absent_when_not_allowed")
                .contains("outbox_absent_when_not_allowed")
                .contains("business_mutation_absent_when_not_allowed")
                .contains("duplicate_mutation_absent")
                .contains("duplicate_outbox_absent")
                .contains("duplicate_success_audit_absent");

        for (LiveRuntimeCheckpoint checkpoint : LiveRuntimeCheckpoint.values()) {
            assertThat(matrix)
                    .as("FDP-38 proof matrix must register checkpoint " + checkpoint)
                    .contains(checkpoint.name());
            assertThat(ci)
                    .as("FDP-38 CI must validate checkpoint " + checkpoint)
                    .contains("node scripts/ci/verify-fdp38-artifacts.mjs");
            assertThat(verifier)
                    .as("FDP-38 artifact verifier must validate checkpoint " + checkpoint)
                    .contains(checkpoint.name())
                    .contains(checkpoint.preconditionSetup().name());
            assertThat(harness)
                    .as("FDP-38 harness must evaluate checkpoint " + checkpoint)
                    .contains("case " + checkpoint.name());
        }
        assertThat(matrix).contains("FUTURE_SCOPE");
    }

    @Test
    void liveRuntimeCheckpointFixtureDockerfileMustNotBeUsedByReleasePaths() throws Exception {
        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        String compose = Files.readString(Path.of("../deployment/docker-compose.yml"));
        String liveCheckpointJob = ci.substring(
                ci.indexOf("fdp38-live-runtime-checkpoint-chaos:"),
                ci.indexOf("\n  fdp39-release-governance:", ci.indexOf("fdp38-live-runtime-checkpoint-chaos:"))
        );
        String releaseGovernanceJob = ci.substring(
                ci.indexOf("fdp39-release-governance:"),
                ci.indexOf("\n  fdp40-release-controls:", ci.indexOf("fdp39-release-governance:"))
        );
        String ciOutsideFixtureJobs = ci.replace(liveCheckpointJob, "").replace(releaseGovernanceJob, "");

        assertThat(compose).doesNotContain("Dockerfile.alert-service-fdp38-fixture");
        assertThat(ciOutsideFixtureJobs).doesNotContain("Dockerfile.alert-service-fdp38-fixture");
        assertThat(liveCheckpointJob).contains("Dockerfile.alert-service-fdp38-fixture");
        assertThat(releaseGovernanceJob).contains("Dockerfile.alert-service-fdp38-fixture");
        assertThat(ci).contains(
                "docker build -f deployment/Dockerfile.backend --build-arg MODULE_NAME=alert-service -t fdp37-alert-service"
        );
    }

    @Test
    void productionImageChaosDocsMustAvoidEnablementOverclaims() throws Exception {
        String adr = Files.readString(Path.of("../docs/adr/fdp_37_production_image_chaos_enable_gate.md"));
        String mergeGate = Files.readString(Path.of("../docs/fdp/fdp_37_merge_gate.md"));
        String checklist = Files.readString(Path.of("../docs/fdp/fdp_37_enablement_decision_checklist.md"));
        String matrix = Files.readString(Path.of("../docs/testing/fdp_37_production_image_chaos_proof.md"));
        String proofPack = Files.readString(Path.of("../docs/testing/fdp_37_production_image_chaos_proof.md"));
        String dashboards = Files.readString(Path.of("../docs/ops/fdp_37_dashboard_and_alert_thresholds.md"));
        String rollbackTemplate = Files.readString(Path.of("../docs/ops/fdp_37_rollback_validation_output_template.md"));
        String combined = adr + "\n" + mergeGate + "\n" + checklist + "\n" + matrix + "\n" + proofPack + "\n" + dashboards + "\n" + rollbackTemplate;

        assertThat(combined).contains("FDP-37 is a proof, operations, and release-gate branch.");
        assertThat(combined).contains("production-like `alert-service` Docker image/container");
        assertThat(combined).contains("PRODUCTION_IMAGE_CONTAINER_KILL");
        assertThat(combined).contains("PRODUCTION_IMAGE_RESTART_API_PROOF");
        assertThat(combined).contains("DURABLE_STATE_SEEDED_CONTAINER_PROOF");
        assertThat(combined).contains("API_PERSISTED_STATE_PROOF");
        assertThat(combined).contains("READY_FOR_ENABLEMENT_REVIEW is not production enablement.");
        assertThat(combined).contains("does not add production chaos hooks");
        assertThat(combined).contains("A skipped live in-flight test is not counted as proof.");
        assertThat(combined).contains("transaction_mode=REQUIRED");
        assertThat(combined).contains("shared Testcontainers network");
        assertThat(combined).contains("network_mode: testcontainers-shared-network");
        assertThat(combined).contains("host_networking_used: false");
        assertThat(combined).contains("DURABLE_STATE_SEEDED");
        assertThat(combined).contains("RUNTIME_REACHED_TEST_FIXTURE");
        assertThat(combined).contains("RUNTIME_REACHED_PRODUCTION_IMAGE");
        assertThat(combined).contains("fdp37-enablement-review-pack.md");
        assertThat(combined).contains("production_enablement: false");
        assertThat(combined).contains("human_approval_required: true");
        assertThat(combined).contains("not production environment configuration certification");
        assertThat(combined).contains("Rollback validation is release evidence, not production rollback approval.");
        assertThat(combined).contains("Do not use command id, alert id, actor id, idempotency key, lease owner, raw exception");
        assertThat(combined).doesNotContain("LIVE_IN_FLIGHT_REQUEST_KILL");
        assertThat(combined).doesNotContain("network_mode: host");
        assertThat(combined).doesNotContain("Linux CI host networking");
        assertThat(combined).doesNotContain("PRODUCTION_ENABLED");
        assertRealChaosForbiddenPhraseIsContextual(combined, "production enablement");
        assertRealChaosForbiddenPhraseIsContextual(combined, "bank certification");
        assertRealChaosForbiddenPhraseIsContextual(combined, "external finality");
        assertRealChaosForbiddenPhraseIsContextual(combined, "distributed ACID");
        assertRealChaosForbiddenPhraseIsContextual(combined, "distributed lock");
        assertRealChaosForbiddenPhraseIsContextual(combined, "Kafka exactly-once");
    }

    @Test
    void productionImageChaosProofMatrixRowsMustMapToConcreteTestsAndCiJobs() throws Exception {
        String matrix = Files.readString(Path.of("../docs/testing/fdp_37_production_image_chaos_proof.md"));

        assertThat(matrix).contains("Scenario | Crash window | State reach method | Killed target | Proof level | Post-restart verification | Invariants checked | Test class/method");
        assertThat(matrix).contains("RegulatedMutationProductionImageChaosIT.productionImageKillAfterClaimBeforeEvidencePreparationDoesNotCommit");
        assertThat(matrix).contains("RegulatedMutationProductionImageChaosIT.productionImageKillInPendingExternalRemainsPendingWithoutEvidence");
        assertThat(matrix).contains("RegulatedMutationProductionImageEvidenceIntegrityIT.finalizedReplayAfterProductionImageRestartDoesNotCreateSecondOutboxRecord");
        assertThat(matrix).contains("RegulatedMutationProductionImageRequiredTransactionChaosIT.requiredTransactionModeFinalizingRestartRequiresRecoveryWithoutFalseSuccess");
        assertThat(matrix).contains("RegulatedMutationProductionImageRollbackIT.rollbackRestartKeepsLeaseFencingAndDoesNotCreateNewSuccessClaims");
        assertThat(matrix).contains("A skipped live in-flight test is not counted as proof.");
        assertThat(matrix).doesNotContain("LIVE_IN_FLIGHT_REQUEST_KILL");
        assertThat(matrix.lines()
                .filter(line -> line.contains("PRODUCTION_IMAGE_CONTAINER_KILL"))
                .toList())
                .allSatisfy(line -> assertThat(line).contains("production-like `alert-service` Docker image/container"));
    }

    @Test
    void productionImageChaosCiMustContainRequiredJobAndArtifacts() throws Exception {
        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        String verifier = Files.readString(Path.of("../scripts/ci/verify-fdp37-artifacts.mjs"));
        String verifierHelpers = Files.readString(Path.of("../scripts/ci/artifact-verification-helpers.mjs"));
        String verifierSurface = verifier + "\n" + verifierHelpers;

        assertThat(ci).contains("fdp37-production-image-chaos:");
        assertThat(ci).contains("Build alert-service production-like image");
        assertThat(ci).contains("docker build -f deployment/Dockerfile.backend --build-arg MODULE_NAME=alert-service -t fdp37-alert-service:${GITHUB_SHA} .");
        assertThat(ci).contains("FDP37_ALERT_SERVICE_IMAGE=fdp37-alert-service:${GITHUB_SHA}");
        assertThat(ci).contains("FDP37_ALERT_SERVICE_IMAGE_ID");
        assertThat(ci).contains("FDP37_ALERT_SERVICE_IMAGE_DIGEST");
        assertThat(ci).contains("-Dfdp37.alert-service.image=${FDP37_ALERT_SERVICE_IMAGE}");
        assertThat(ci).contains("-Dfdp37.alert-service.image-id=${FDP37_ALERT_SERVICE_IMAGE_ID}");
        assertThat(ci).contains("-Dfdp37.alert-service.image-digest=${FDP37_ALERT_SERVICE_IMAGE_DIGEST}");
        assertThat(ci).contains("RegulatedMutationProductionImageChaosIT");
        assertThat(ci).contains("RegulatedMutationProductionImageEvidenceIntegrityIT");
        assertThat(ci).contains("RegulatedMutationProductionImageConfigParityIT");
        assertThat(ci).contains("RegulatedMutationProductionImageRollbackIT");
        assertThat(ci).contains("RegulatedMutationProductionImageRequiredTransactionChaosIT");
        assertThat(ci).contains("node scripts/ci/verify-fdp37-artifacts.mjs");
        assertThat(verifierSurface).contains("test class did not fully execute");
        assertThat(verifier).contains("fdp37-proof-summary.md");
        assertThat(verifier).contains("fdp37-proof-summary.json");
        assertThat(verifier).contains("fdp37-enablement-review-pack.md");
        assertThat(verifier).contains("fdp37-enablement-review-pack.json");
        assertThat(verifier).contains("fdp37-rollback-validation.md");
        assertThat(verifier).contains("PRODUCTION_IMAGE_CONTAINER_KILL");
        assertThat(verifier).contains("PRODUCTION_IMAGE_RESTART_API_PROOF");
        assertThat(verifier).contains("transaction_mode=REQUIRED");
        assertThat(verifier).contains("network_mode=testcontainers-shared-network");
        assertThat(verifier).contains("host_networking_used=false");
        assertThat(verifier).contains("FDP-37 proof artifacts must not use host networking");
        assertThat(verifier).contains("FDP-37 enablement review pack");
        assertThat(verifier).contains("scenario_count");
        assertThat(verifier).contains("live_in_flight_proof_executed: `false`");
        assertThat(ci).contains("if-no-files-found: error");
        assertThat(ci).contains("fdp37-production-image-chaos-reports");
        assertThat(ci).contains("regulated-mutation-regression");
        assertThat(ci).contains("fdp36-real-chaos");
        assertThat(ci).contains("fdp35-production-readiness");
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
                    .as("Forbidden overclaim wording must be negated, limited, or future-contextual: " + phrase)
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

    private void assertRealChaosForbiddenPhraseIsContextual(String source, String phrase) {
        String lowerSource = source.toLowerCase(java.util.Locale.ROOT);
        String lowerPhrase = phrase.toLowerCase(java.util.Locale.ROOT);
        int index = lowerSource.indexOf(lowerPhrase);
        while (index >= 0) {
            int start = Math.max(0, index - 220);
            int end = Math.min(lowerSource.length(), index + lowerPhrase.length() + 220);
            String context = lowerSource.substring(start, end);
            assertThat(context)
                    .as("FDP-36 forbidden wording must be negated, forbidden, or future-review contextual: " + phrase)
                    .containsAnyOf(
                            "does not",
                            "do not",
                            "not ",
                            "no ",
                            "never",
                            "must not",
                            "without",
                            "forbidden",
                            "non-goal",
                            "future",
                            "review",
                            "not production enablement"
                    );
            index = lowerSource.indexOf(lowerPhrase, index + lowerPhrase.length());
        }
    }

    private void assertLiveCheckpointForbiddenPhraseIsContextual(String source, String phrase) {
        String lowerSource = source.toLowerCase(java.util.Locale.ROOT);
        String lowerPhrase = phrase.toLowerCase(java.util.Locale.ROOT);
        int index = lowerSource.indexOf(lowerPhrase);
        while (index >= 0) {
            int start = Math.max(0, index - 500);
            int end = Math.min(lowerSource.length(), index + lowerPhrase.length() + 500);
            String context = lowerSource.substring(start, end);
            assertThat(context)
                    .as("FDP-38 forbidden wording must be negated, no-claim, or future-scope contextual: " + phrase)
                    .containsAnyOf(
                            "does not",
                            "do not",
                            "not ",
                            "no ",
                            "never",
                            "must not",
                            "no-go",
                            "non-claims",
                            "future",
                            "without claiming",
                            "not production enablement",
                            "is not a production image",
                            "if any artifact claims"
                    );
            index = lowerSource.indexOf(lowerPhrase, index + lowerPhrase.length());
        }
    }
}
