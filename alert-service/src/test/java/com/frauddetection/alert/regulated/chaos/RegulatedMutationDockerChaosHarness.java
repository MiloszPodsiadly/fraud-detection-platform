package com.frauddetection.alert.regulated.chaos;

import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

public final class RegulatedMutationDockerChaosHarness implements AutoCloseable {

    private static final DockerImageName PROCESS_IMAGE = DockerImageName.parse("alpine:3.20");

    private final MongoTemplate mongoTemplate;
    private GenericContainer<?> serviceContainer;
    private String killedContainerId;
    private String restartedContainerId;

    public RegulatedMutationDockerChaosHarness(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public RegulatedMutationChaosResult run(RegulatedMutationChaosScenario scenario) {
        startServiceContainer();
        scenario.seedDurableState().accept(mongoTemplate);
        killContainerAbruptly();
        restartServiceContainer();
        return collectEvidence(scenario);
    }

    public void startServiceContainer() {
        serviceContainer = new GenericContainer<>(PROCESS_IMAGE)
                .withCommand("sh", "-c", "while true; do sleep 1; done");
        serviceContainer.start();
        if (!serviceContainer.isRunning()) {
            throw new IllegalStateException("FDP-36 test service container did not become ready");
        }
    }

    public void killContainerAbruptly() {
        if (serviceContainer == null || serviceContainer.getContainerId() == null) {
            throw new IllegalStateException("FDP-36 test service container was not started");
        }
        killedContainerId = serviceContainer.getContainerId();
        DockerClientFactory.instance().client().killContainerCmd(killedContainerId).exec();
    }

    public void restartServiceContainer() {
        serviceContainer = new GenericContainer<>(PROCESS_IMAGE)
                .withCommand("sh", "-c", "while true; do sleep 1; done");
        serviceContainer.start();
        if (!serviceContainer.isRunning()) {
            throw new IllegalStateException("FDP-36 restarted test service container did not become ready");
        }
        restartedContainerId = serviceContainer.getContainerId();
    }

    public RegulatedMutationChaosResult collectEvidence(RegulatedMutationChaosScenario scenario) {
        RegulatedMutationCommandDocument command = mongoTemplate.findById(
                scenario.commandId(),
                RegulatedMutationCommandDocument.class
        );
        if (command == null) {
            throw new IllegalStateException("FDP-36 scenario command is missing: " + scenario.commandId());
        }
        Optional<AlertDocument> alert = Optional.ofNullable(mongoTemplate.findById(command.getResourceId(), AlertDocument.class));
        return new RegulatedMutationChaosResult(
                scenario.name(),
                scenario.window(),
                killedContainerId,
                restartedContainerId,
                killedContainerId != null,
                restartedContainerId != null,
                command.getState(),
                command.getExecutionStatus(),
                command.getPublicStatus(),
                command.getResponseSnapshot() != null,
                alert.map(AlertDocument::getAlertStatus).orElse(null),
                alert.map(AlertDocument::getAnalystDecision).orElse(null),
                countOutbox(command.getId()),
                countAudit(command.getResourceId(), AuditOutcome.ATTEMPTED),
                countAudit(command.getResourceId(), AuditOutcome.SUCCESS),
                alert.map(AlertDocument::getAnalystDecision).isPresent() ? 1L : 0L
        );
    }

    private long countOutbox(String commandId) {
        return mongoTemplate.count(
                Query.query(Criteria.where("mutation_command_id").is(commandId)),
                TransactionalOutboxRecordDocument.class
        );
    }

    private long countAudit(String resourceId, AuditOutcome outcome) {
        return mongoTemplate.getCollection("audit_events")
                .countDocuments(new org.bson.Document("resource_id", resourceId)
                        .append("outcome", outcome.name()));
    }

    @Override
    public void close() {
        if (serviceContainer != null) {
            serviceContainer.close();
        }
    }
}
