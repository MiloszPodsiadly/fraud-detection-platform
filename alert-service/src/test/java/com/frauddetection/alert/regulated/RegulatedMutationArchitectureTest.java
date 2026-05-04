package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
