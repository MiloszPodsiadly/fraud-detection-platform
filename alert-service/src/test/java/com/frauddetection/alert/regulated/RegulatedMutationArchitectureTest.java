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
        String handlerSource = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/regulated/mutation/submitdecision/SubmitDecisionMutationHandler.java"
        ));

        assertThat(serviceSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(coordinatorSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(handlerSource).doesNotContain("FraudDecisionEventPublisher");
        assertThat(serviceSource).doesNotContain(".publish(");
        assertThat(coordinatorSource).doesNotContain(".publish(");
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
            assertThat(source).as("regulated mutation handler must not write phase audit directly: " + handler)
                    .doesNotContain("auditService.audit");
        }
    }

    @Test
    void trustIncidentReadsMustRemainReadOnly() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/frauddetection/alert/trust/TrustIncidentService.java"
        ));
        String listMethod = service.substring(service.indexOf("public List<TrustIncidentResponse> listOpen()"),
                service.indexOf("public TrustIncidentResponse acknowledge("));
        String summaryMethod = service.substring(service.indexOf("public TrustIncidentSummary summary()"),
                service.indexOf("private RegulatedMutationIntent intent("));

        assertThat(listMethod).doesNotContain("repository.save");
        assertThat(listMethod).doesNotContain("materializer.materialize");
        assertThat(summaryMethod).doesNotContain("repository.save");
        assertThat(summaryMethod).doesNotContain("materializer.materialize");
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
    void docsMustNotClaimExactlyOnceOrDistributedAcidForFdp26A() throws Exception {
        String readme = Files.readString(Path.of("../README.md"));
        String api = Files.readString(Path.of("../docs/api-surface-v1.md"));
        String security = Files.readString(Path.of("../docs/security-foundation-v1.md"));
        String combined = readme + "\n" + api + "\n" + security;

        assertThat(combined).doesNotContain("provides exactly-once");
        assertThat(combined).doesNotContain("guarantees exactly-once");
        assertThat(combined).doesNotContain("provides distributed ACID");
        assertThat(combined).doesNotContain("guarantees distributed ACID");
        assertThat(combined).contains("not distributed ACID");
        assertThat(combined).contains("does not provide exactly-once");
    }
}
