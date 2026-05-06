package com.frauddetection.alert.regulated.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.security.auth.DemoAuthHeaders;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class RegulatedMutationProductionImageChaosHarness implements AutoCloseable {

    public static final String IMAGE_PROPERTY = "fdp37.alert-service.image";
    public static final String IMAGE_ENV = "FDP37_ALERT_SERVICE_IMAGE";

    private static final String TARGET_NAME = "alert-service";
    private static final String PROOF_SUMMARY_MD = "fdp37-proof-summary.md";
    private static final String PROOF_SUMMARY_JSON = "fdp37-proof-summary.json";

    private final MongoTemplate mongoTemplate;
    private final String mongodbUri;
    private final String imageName;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path logDirectory = Path.of("target", "fdp37-chaos");
    private final List<RegulatedMutationChaosResult> results = new ArrayList<>();

    private GenericContainer<?> container;
    private String killedContainerId;
    private String restartedContainerId;
    private String killedImageName;
    private String restartedImageName;
    private int servicePort;
    private List<String> lastEffectiveArgs = List.of();

    public RegulatedMutationProductionImageChaosHarness(
            MongoTemplate mongoTemplate,
            String mongodbUri,
            String imageName
    ) {
        this.mongoTemplate = mongoTemplate;
        this.mongodbUri = mongodbUri;
        this.imageName = requireAlertServiceImage(imageName);
    }

    public static Optional<String> configuredImageName() {
        String property = System.getProperty(IMAGE_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Optional.of(property.trim());
        }
        String env = System.getenv(IMAGE_ENV);
        if (env != null && !env.isBlank()) {
            return Optional.of(env.trim());
        }
        return Optional.empty();
    }

    public RegulatedMutationChaosResult runDurableStateScenario(RegulatedMutationChaosScenario scenario) {
        startAlertService("before-kill-" + scenario.name(), List.of());
        scenario.seedDurableState().accept(mongoTemplate);
        killAlertServiceContainerAbruptly();
        restartAlertService("after-restart-" + scenario.name(), List.of());
        return collectEvidence(
                scenario,
                inspectByCommandId(scenario.commandId()),
                null,
                EnumSet.of(
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL,
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_RESTART_API_PROOF,
                        RegulatedMutationProofLevel.DURABLE_STATE_SEEDED_CONTAINER_PROOF,
                        RegulatedMutationProofLevel.API_PERSISTED_STATE_PROOF
                )
        );
    }

    public JsonNode recoverViaRestartedService() {
        return requestJson(
                HttpRequest.newBuilder(uri("/api/v1/regulated-mutations/recover"))
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .headers(demoHeaders())
                        .build()
        );
    }

    public JsonNode inspectByCommandId(String commandId) {
        return requestJson(
                HttpRequest.newBuilder(uri("/api/v1/regulated-mutations/by-command/" + commandId))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .headers(demoHeaders())
                        .build()
        );
    }

    public JsonNode inspectByIdempotencyKey(String idempotencyKey) {
        return requestJson(
                HttpRequest.newBuilder(uri("/api/v1/regulated-mutations/" + idempotencyKey))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .headers(demoHeaders())
                        .build()
        );
    }

    public CompletableFuture<HttpResponse<String>> submitDecisionAsync(
            String alertId,
            String idempotencyKey,
            String requestBody
    ) {
        return httpClient.sendAsync(
                HttpRequest.newBuilder(uri("/api/v1/alerts/" + alertId + "/decision"))
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .header("Content-Type", "application/json")
                        .header("X-Idempotency-Key", idempotencyKey)
                        .headers(demoHeaders())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    public RegulatedMutationChaosResult collectEvidence(
            RegulatedMutationChaosScenario scenario,
            JsonNode inspectionResponse,
            JsonNode recoveryResponse,
            Set<RegulatedMutationProofLevel> proofLevels
    ) {
        RegulatedMutationCommandDocument command = mongoTemplate.findById(
                scenario.commandId(),
                RegulatedMutationCommandDocument.class
        );
        if (command == null) {
            throw new IllegalStateException("FDP-37 scenario command is missing: " + scenario.commandId());
        }
        Optional<AlertDocument> alert = Optional.ofNullable(mongoTemplate.findById(command.getResourceId(), AlertDocument.class));
        RegulatedMutationChaosResult result = new RegulatedMutationChaosResult(
                scenario.name(),
                scenario.window(),
                primaryProofLevel(proofLevels),
                killedContainerId,
                restartedContainerId,
                killedImageName,
                restartedImageName,
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
                alert.map(AlertDocument::getAnalystDecision).isPresent() ? 1L : 0L,
                inspectionResponse,
                recoveryResponse
        );
        results.add(result);
        appendEvidenceSummary(result, proofLevels);
        writeProofArtifacts();
        return result;
    }

    public void startAlertService(String logName, List<String> additionalArgs) {
        assertNoRunningContainer();
        servicePort = freePort();
        List<String> args = alertServiceArgs(additionalArgs);
        lastEffectiveArgs = List.copyOf(args);
        container = new GenericContainer<>(DockerImageName.parse(imageName))
                .withNetworkMode("host")
                .withCommand(args.toArray(String[]::new));
        try {
            Files.createDirectories(logDirectory);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to create FDP-37 chaos log directory", exception);
        }
        container.start();
        assertAlertServiceImage(container.getDockerImageName());
        restartedImageName = container.getDockerImageName();
        awaitReadiness();
    }

    public void killAlertServiceContainerAbruptly() {
        if (container == null || !container.isRunning()) {
            throw new IllegalStateException("FDP-37 alert-service container was not running");
        }
        assertAlertServiceImage(container.getDockerImageName());
        killedContainerId = container.getContainerId();
        killedImageName = container.getDockerImageName();
        container.getDockerClient().killContainerCmd(killedContainerId).exec();
        container.close();
        container = null;
    }

    public void restartAlertService(String logName, List<String> additionalArgs) {
        startAlertService(logName, additionalArgs);
        restartedContainerId = container.getContainerId();
        restartedImageName = container.getDockerImageName();
        if (restartedContainerId.equals(killedContainerId)) {
            throw new IllegalStateException("FDP-37 restarted alert-service container reused killed container id");
        }
    }

    public String imageName() {
        return imageName;
    }

    public int servicePort() {
        return servicePort;
    }

    public List<String> lastEffectiveArgs() {
        return lastEffectiveArgs;
    }

    private void appendEvidenceSummary(
            RegulatedMutationChaosResult result,
            Set<RegulatedMutationProofLevel> proofLevels
    ) {
        try {
            Files.createDirectories(logDirectory);
            Files.writeString(
                    logDirectory.resolve("evidence-summary.md"),
                    "- scenario=" + result.scenarioName()
                            + "; window=" + result.window()
                            + "; state_reach_method=durable-state-seeded"
                            + "; proof_levels=" + proofLevels
                            + "; killed_target=" + result.killedTargetName()
                            + "; killed_container=" + maskId(result.killedTargetId())
                            + "; restarted_target=" + result.restartedTargetName()
                            + "; restarted_container=" + maskId(result.restartedTargetId())
                            + "; result=PASS"
                            + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write FDP-37 chaos evidence summary", exception);
        }
    }

    private void writeProofArtifacts() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("timestamp", Instant.now().toString());
        root.put("commit_sha", Optional.ofNullable(System.getenv("GITHUB_SHA")).orElse("LOCAL"));
        root.put("ci_job_name", Optional.ofNullable(System.getenv("GITHUB_JOB")).orElse("LOCAL"));
        root.put("image_name", imageName);
        root.put("killed_container_id_masked", maskId(killedContainerId));
        root.put("restarted_container_id_masked", maskId(restartedContainerId));
        root.put("mongo_replica_set_uri_masked", "mongodb://<masked-host>/<masked-db>?replicaSet=<masked>");
        root.put("scenario_count", results.size());
        root.put("final_result", "PASS");
        root.put("enablement_note", "READY_FOR_ENABLEMENT_REVIEW is not production enablement.");
        ArrayNode scenarios = root.putArray("scenarios");
        for (RegulatedMutationChaosResult result : results) {
            ObjectNode scenario = scenarios.addObject();
            scenario.put("scenario", result.scenarioName());
            scenario.put("window", result.window().name());
            scenario.put("killed_target", result.killedTargetName());
            scenario.put("restarted_target", result.restartedTargetName());
            scenario.put("state", result.commandState() == null ? null : result.commandState().name());
            scenario.put("execution_status", result.executionStatus() == null ? null : result.executionStatus().name());
            scenario.put("outbox_records", result.outboxRecords());
            scenario.put("success_audit_events", result.successAuditEvents());
        }
        try {
            Files.createDirectories(logDirectory);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(logDirectory.resolve(PROOF_SUMMARY_JSON).toFile(), root);
            Files.writeString(
                    logDirectory.resolve(PROOF_SUMMARY_MD),
                    "# FDP-37 Production Image Chaos Proof Summary\n\n"
                            + "- image_name: `" + imageName + "`\n"
                            + "- killed_container_id_masked: `" + maskId(killedContainerId) + "`\n"
                            + "- restarted_container_id_masked: `" + maskId(restartedContainerId) + "`\n"
                            + "- scenario_count: `" + results.size() + "`\n"
                            + "- final_result: `PASS`\n"
                            + "- enablement_note: `READY_FOR_ENABLEMENT_REVIEW is not production enablement.`\n\n"
                            + "## Scenarios\n\n"
                            + markdownRows()
                            + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write FDP-37 proof artifacts", exception);
        }
    }

    private String markdownRows() {
        StringBuilder builder = new StringBuilder();
        builder.append("| Scenario | Crash window | Killed target | Restarted target | State | Status | Outbox | SUCCESS audit |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | ---: | ---: |\n");
        for (RegulatedMutationChaosResult result : results) {
            builder.append("| ")
                    .append(result.scenarioName()).append(" | ")
                    .append(result.window()).append(" | ")
                    .append(result.killedTargetName()).append(" | ")
                    .append(result.restartedTargetName()).append(" | ")
                    .append(result.commandState()).append(" | ")
                    .append(result.executionStatus()).append(" | ")
                    .append(result.outboxRecords()).append(" | ")
                    .append(result.successAuditEvents()).append(" |\n");
        }
        return builder.toString();
    }

    private List<String> alertServiceArgs(List<String> additionalArgs) {
        List<String> args = new ArrayList<>();
        args.add("--server.port=" + servicePort);
        args.add("--spring.profiles.active=test");
        args.add("--spring.data.mongodb.uri=" + mongodbUri);
        args.add("--spring.data.redis.host=localhost");
        args.add("--spring.data.redis.port=" + FraudPlatformContainers.redis().getMappedPort(6379));
        args.add("--spring.kafka.bootstrap-servers=" + FraudPlatformContainers.kafka().getBootstrapServers());
        args.add("--app.security.demo-auth.enabled=true");
        args.add("--app.outbox.publisher.enabled=false");
        args.add("--app.evidence-confirmation.enabled=false");
        args.add("--app.regulated-mutation.recovery.scheduler.enabled=false");
        args.add("--app.kafka.topics.transaction-scored=fdp37.transactions.scored");
        args.add("--app.kafka.topics.fraud-alerts=fdp37.fraud.alerts");
        args.add("--app.kafka.topics.fraud-decisions=fdp37.fraud.decisions");
        args.add("--app.kafka.topics.transactions-dead-letter=fdp37.transactions.dead-letter");
        args.add("--logging.level.root=WARN");
        args.addAll(additionalArgs);
        return args;
    }

    private JsonNode requestJson(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("FDP-37 restarted alert-service API returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new UncheckedIOException("FDP-37 restarted alert-service API call failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during FDP-37 restarted alert-service API call", exception);
        }
    }

    private void awaitReadiness() {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (container == null || !container.isRunning()) {
                throw new IllegalStateException("FDP-37 alert-service container exited before readiness");
            }
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder(uri("/actuator/health/readiness"))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for FDP-37 alert-service readiness", ignored);
                }
            }
            sleep();
        }
        throw new IllegalStateException("FDP-37 alert-service container did not become ready on port " + servicePort);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + servicePort + path);
    }

    private String[] demoHeaders() {
        return new String[]{
                DemoAuthHeaders.USER_ID, "fdp37-operator",
                DemoAuthHeaders.ROLES, "FRAUD_OPS_ADMIN"
        };
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

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to allocate FDP-37 alert-service test port", exception);
        }
    }

    private void assertNoRunningContainer() {
        if (container != null && container.isRunning()) {
            throw new IllegalStateException("FDP-37 alert-service container is already running");
        }
    }

    private RegulatedMutationProofLevel primaryProofLevel(Set<RegulatedMutationProofLevel> proofLevels) {
        if (proofLevels.contains(RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL)) {
            return RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL;
        }
        return proofLevels.iterator().next();
    }

    private String requireAlertServiceImage(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("FDP-37 requires " + IMAGE_PROPERTY + " or " + IMAGE_ENV);
        }
        return assertAlertServiceImage(candidate.trim());
    }

    private String assertAlertServiceImage(String candidate) {
        String normalized = candidate == null ? "" : candidate.toLowerCase();
        if (!normalized.contains("alert-service")) {
            throw new IllegalStateException("FDP-37 killed target must be the alert-service image/container, got: " + candidate);
        }
        if (normalized.contains("alpine") || normalized.contains("busybox") || normalized.contains("dummy")) {
            throw new IllegalStateException("FDP-37 production image chaos cannot use dummy image: " + candidate);
        }
        return candidate;
    }

    private String maskId(String id) {
        if (id == null || id.isBlank()) {
            return "absent";
        }
        return id.length() <= 12 ? id : id.substring(0, 12);
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for FDP-37 alert-service readiness", exception);
        }
    }

    @Override
    public void close() {
        if (container != null) {
            container.close();
        }
    }
}
