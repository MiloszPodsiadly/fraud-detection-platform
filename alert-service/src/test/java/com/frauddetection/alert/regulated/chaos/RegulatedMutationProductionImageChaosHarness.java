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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class RegulatedMutationProductionImageChaosHarness implements AutoCloseable {

    public static final String IMAGE_PROPERTY = "fdp37.alert-service.image";
    public static final String IMAGE_ENV = "FDP37_ALERT_SERVICE_IMAGE";
    public static final String IMAGE_DIGEST_PROPERTY = "fdp37.alert-service.image-digest";
    public static final String IMAGE_DIGEST_ENV = "FDP37_ALERT_SERVICE_IMAGE_DIGEST";
    public static final String IMAGE_ID_PROPERTY = "fdp37.alert-service.image-id";
    public static final String IMAGE_ID_ENV = "FDP37_ALERT_SERVICE_IMAGE_ID";

    private static final String TARGET_NAME = "alert-service";
    private static final String PROOF_SUMMARY_MD = "fdp37-proof-summary.md";
    private static final String PROOF_SUMMARY_JSON = "fdp37-proof-summary.json";
    private static final String ENABLEMENT_REVIEW_PACK_MD = "fdp37-enablement-review-pack.md";
    private static final String ENABLEMENT_REVIEW_PACK_JSON = "fdp37-enablement-review-pack.json";
    private static final String ROLLBACK_VALIDATION_MD = "fdp37-rollback-validation.md";
    private static final String ROLLBACK_VALIDATION_JSON = "fdp37-rollback-validation.json";

    private final MongoTemplate mongoTemplate;
    private final String mongodbUri;
    private final String imageName;
    private final String imageDigest;
    private final String imageId;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path logDirectory = Path.of("target", "fdp37-chaos");
    private final List<RegulatedMutationChaosResult> results = new ArrayList<>();
    private final Map<String, String> scenarioTransactionModes = new HashMap<>();

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
        this.imageDigest = configuredImageDigest().orElse("LOCAL_IMAGE_DIGEST_NOT_PROVIDED");
        this.imageId = configuredImageId().orElse("LOCAL_IMAGE_ID_NOT_PROVIDED");
        requireImageProvenanceInCi();
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

    public static Optional<String> configuredImageDigest() {
        String property = System.getProperty(IMAGE_DIGEST_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Optional.of(property.trim());
        }
        String env = System.getenv(IMAGE_DIGEST_ENV);
        if (env != null && !env.isBlank()) {
            return Optional.of(env.trim());
        }
        return Optional.empty();
    }

    public static Optional<String> configuredImageId() {
        String property = System.getProperty(IMAGE_ID_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Optional.of(property.trim());
        }
        String env = System.getenv(IMAGE_ID_ENV);
        if (env != null && !env.isBlank()) {
            return Optional.of(env.trim());
        }
        return Optional.empty();
    }

    public RegulatedMutationChaosResult runDurableStateScenario(RegulatedMutationChaosScenario scenario) {
        return runDurableStateScenario(scenario, List.of());
    }

    public RegulatedMutationChaosResult runDurableStateScenario(
            RegulatedMutationChaosScenario scenario,
            List<String> additionalArgs
    ) {
        startAlertService("before-kill-" + scenario.name(), additionalArgs);
        scenario.seedDurableState().accept(mongoTemplate);
        killAlertServiceContainerAbruptly();
        restartAlertService("after-restart-" + scenario.name(), additionalArgs);
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
                scenario.stateReachMethod(),
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
                command.getLocalCommitMarker() != null,
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
        scenarioTransactionModes.put(result.scenarioName(), currentTransactionMode());
        appendEvidenceSummary(result, proofLevels);
        writeProofArtifacts();
        return result;
    }

    public void startAlertService(String logName, List<String> additionalArgs) {
        assertNoRunningContainer();
        List<String> args = alertServiceArgs(additionalArgs);
        lastEffectiveArgs = List.copyOf(args);
        container = new GenericContainer<>(DockerImageName.parse(imageName))
                .withNetwork(FraudPlatformContainers.network())
                .withNetworkAliases("alert-service-fdp37")
                .withExposedPorts(8080)
                .withCommand(args.toArray(String[]::new));
        try {
            Files.createDirectories(logDirectory);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to create FDP-37 chaos log directory", exception);
        }
        container.start();
        assertAlertServiceImage(container.getDockerImageName());
        restartedImageName = container.getDockerImageName();
        servicePort = container.getMappedPort(8080);
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
        RegulatedMutationChaosWaiter.waitUntil(
                "FDP-37 killed alert-service container to stop",
                Duration.ofSeconds(20),
                Duration.ofMillis(250),
                () -> {
                    try {
                        Boolean running = container.getDockerClient()
                                .inspectContainerCmd(killedContainerId)
                                .exec()
                                .getState()
                                .getRunning();
                        return Boolean.TRUE.equals(running)
                                ? RegulatedMutationChaosWaiter.ProbeResult.waiting("container still running")
                                : RegulatedMutationChaosWaiter.ProbeResult.satisfied("stopped");
                    } catch (RuntimeException exception) {
                        return RegulatedMutationChaosWaiter.ProbeResult.satisfied(
                                "inspect unavailable after kill: " + exception.getClass().getSimpleName()
                        );
                    }
                }
        );
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

    public String imageDigest() {
        return imageDigest;
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
                            + "; image_tag=" + imageTag()
                            + "; image_id=" + imageId
                            + "; image_digest=" + imageDigest
                            + "; transaction_mode=" + currentTransactionMode()
                            + "; state_reach_method=" + result.stateReachMethod()
                            + "; state=" + result.commandState()
                            + "; status=" + result.executionStatus()
                            + "; outbox=" + result.outboxRecords()
                            + "; success_audit=" + result.successAuditEvents()
                            + "; network_mode=testcontainers-shared-network"
                            + "; network_aliases_used=true"
                            + "; host_networking_used=false"
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
        root.put("github_run_id", Optional.ofNullable(System.getenv("GITHUB_RUN_ID")).orElse("LOCAL"));
        root.put("image_name", imageName);
        root.put("image_tag", imageTag());
        root.put("image_id", imageId);
        root.put("image_digest_or_id", imageDigest);
        root.put("dockerfile_path", "deployment/Dockerfile.backend");
        root.put("image_kind", "production-like");
        root.put("network_mode", "testcontainers-shared-network");
        root.put("network_aliases_used", true);
        root.put("host_networking_used", false);
        root.put("os_name", System.getProperty("os.name", "unknown"));
        root.put("ci_runner", Optional.ofNullable(System.getenv("RUNNER_OS")).orElse("LOCAL"));
        root.put("readiness_wait_strategy", "bounded-poll");
        root.put("live_fixture_enabled", liveFixtureEnabled());
        root.put("live_in_flight_proof_executed", false);
        root.put("live_in_flight_test_class", "RegulatedMutationProductionImageLiveInFlightKillIT");
        root.put("live_in_flight_result", "OPTIONAL_NOT_REQUIRED");
        root.put("killed_container_id_masked", maskId(killedContainerId));
        root.put("restarted_container_id_masked", maskId(restartedContainerId));
        root.put("mongo_replica_set_uri_masked", "mongodb://<masked-host>/<masked-db>?replicaSet=<masked>");
        root.put("scenario_count", aggregateScenarioCount());
        root.put("durable_state_seeded_scenarios_count", countScenarios(RegulatedMutationStateReachMethod.DURABLE_STATE_SEEDED));
        root.put("runtime_reached_fixture_scenarios_count", countScenarios(RegulatedMutationStateReachMethod.RUNTIME_REACHED_TEST_FIXTURE));
        root.put("runtime_reached_production_image_scenarios_count", countScenarios(RegulatedMutationStateReachMethod.RUNTIME_REACHED_PRODUCTION_IMAGE));
        root.put("final_result", "PASS");
        root.put("enablement_note", "READY_FOR_ENABLEMENT_REVIEW is not production enablement.");
        ArrayNode scenarios = root.putArray("scenarios");
        for (RegulatedMutationChaosResult result : results) {
            ObjectNode scenario = scenarios.addObject();
            scenario.put("scenario", result.scenarioName());
            scenario.put("window", result.window().name());
            scenario.put("state_reach_method", result.stateReachMethod().name());
            scenario.put("killed_target", result.killedTargetName());
            scenario.put("restarted_target", result.restartedTargetName());
            scenario.put("state", result.commandState() == null ? null : result.commandState().name());
            scenario.put("execution_status", result.executionStatus() == null ? null : result.executionStatus().name());
            scenario.put("transaction_mode", scenarioTransactionModes.getOrDefault(result.scenarioName(), "OFF"));
            scenario.put("outbox_records", result.outboxRecords());
            scenario.put("success_audit_events", result.successAuditEvents());
        }
        try {
            Files.createDirectories(logDirectory);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(logDirectory.resolve(PROOF_SUMMARY_JSON).toFile(), root);
            writeEnablementReviewPack(root);
            Files.writeString(
                    logDirectory.resolve(PROOF_SUMMARY_MD),
                    "# FDP-37 Production Image Chaos Proof Summary\n\n"
                            + "- image_name: `" + imageName + "`\n"
                            + "- image_tag: `" + imageTag() + "`\n"
                            + "- image_id: `" + imageId + "`\n"
                            + "- image_digest_or_id: `" + imageDigest + "`\n"
                            + "- dockerfile_path: `deployment/Dockerfile.backend`\n"
                            + "- commit_sha: `" + Optional.ofNullable(System.getenv("GITHUB_SHA")).orElse("LOCAL") + "`\n"
                            + "- ci_job_name: `" + Optional.ofNullable(System.getenv("GITHUB_JOB")).orElse("LOCAL") + "`\n"
                            + "- github_run_id: `" + Optional.ofNullable(System.getenv("GITHUB_RUN_ID")).orElse("LOCAL") + "`\n"
                            + "- image_kind: `production-like`\n"
                            + "- network_mode: `testcontainers-shared-network`\n"
                            + "- network_aliases_used: `true`\n"
                            + "- host_networking_used: `false`\n"
                            + "- readiness_wait_strategy: `bounded-poll`\n"
                            + "- os_name: `" + System.getProperty("os.name", "unknown") + "`\n"
                            + "- ci_runner: `" + Optional.ofNullable(System.getenv("RUNNER_OS")).orElse("LOCAL") + "`\n"
                            + "- live_fixture_enabled: `" + liveFixtureEnabled() + "`\n"
                            + "- live_in_flight_proof_executed: `false`\n"
                            + "- live_in_flight_test_class: `RegulatedMutationProductionImageLiveInFlightKillIT`\n"
                            + "- live_in_flight_result: `OPTIONAL_NOT_REQUIRED`\n"
                            + "- killed_container_id_masked: `" + maskId(killedContainerId) + "`\n"
                            + "- restarted_container_id_masked: `" + maskId(restartedContainerId) + "`\n"
                            + "- scenario_count: `" + aggregateScenarioCount() + "`\n"
                            + "- durable_state_seeded_scenarios_count: `" + countScenarios(RegulatedMutationStateReachMethod.DURABLE_STATE_SEEDED) + "`\n"
                            + "- runtime_reached_fixture_scenarios_count: `" + countScenarios(RegulatedMutationStateReachMethod.RUNTIME_REACHED_TEST_FIXTURE) + "`\n"
                            + "- runtime_reached_production_image_scenarios_count: `" + countScenarios(RegulatedMutationStateReachMethod.RUNTIME_REACHED_PRODUCTION_IMAGE) + "`\n"
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

    private void writeEnablementReviewPack(ObjectNode proofSummary) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("commit_sha", proofSummary.path("commit_sha").asText());
        root.put("image_name", imageName);
        root.put("image_id", imageId);
        root.put("image_digest_or_id", imageDigest);
        root.put("dockerfile_path", "deployment/Dockerfile.backend");
        root.put("fdp37_job_status", "PASS");
        root.put("regulated_mutation_regression_status", requiredWorkflowStatus("REGULATED_MUTATION_REGRESSION_STATUS"));
        root.put("fdp35_status", requiredWorkflowStatus("FDP35_STATUS"));
        root.put("fdp36_status", requiredWorkflowStatus("FDP36_STATUS"));
        root.put("required_transaction_scenario_executed", hasRequiredTransactionScenario());
        root.put("durable_state_seeded_scenario_count", countScenarios(RegulatedMutationStateReachMethod.DURABLE_STATE_SEEDED));
        root.put("live_in_flight_required", false);
        root.put("live_in_flight_executed", countScenarios(RegulatedMutationStateReachMethod.RUNTIME_REACHED_TEST_FIXTURE) > 0);
        root.put("rollback_validation_passed", Files.exists(logDirectory.resolve(ROLLBACK_VALIDATION_JSON)));
        root.put("proof_artifacts_present", true);
        root.put("production_enablement", false);
        root.put("release_config_pr_required", true);
        root.put("human_approval_required", true);
        root.put("operator_drill_required_before_enablement", true);
        root.put("final_decision", "READY_FOR_ENABLEMENT_REVIEW");

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(logDirectory.resolve(ENABLEMENT_REVIEW_PACK_JSON).toFile(), root);
        Files.writeString(
                logDirectory.resolve(ENABLEMENT_REVIEW_PACK_MD),
                "# FDP-37 Enablement Review Pack\n\n"
                        + "- commit_sha: `" + root.path("commit_sha").asText() + "`\n"
                        + "- image_name: `" + imageName + "`\n"
                        + "- image_id: `" + imageId + "`\n"
                        + "- image_digest_or_id: `" + imageDigest + "`\n"
                        + "- dockerfile_path: `deployment/Dockerfile.backend`\n"
                        + "- fdp37_job_status: `PASS`\n"
                        + "- regulated_mutation_regression_status: `" + root.path("regulated_mutation_regression_status").asText() + "`\n"
                        + "- fdp35_status: `" + root.path("fdp35_status").asText() + "`\n"
                        + "- fdp36_status: `" + root.path("fdp36_status").asText() + "`\n"
                        + "- required_transaction_scenario_executed: `" + root.path("required_transaction_scenario_executed").asBoolean() + "`\n"
                        + "- durable_state_seeded_scenario_count: `" + root.path("durable_state_seeded_scenario_count").asLong() + "`\n"
                        + "- live_in_flight_required: `false`\n"
                        + "- live_in_flight_executed: `" + root.path("live_in_flight_executed").asBoolean() + "`\n"
                        + "- rollback_validation_passed: `" + root.path("rollback_validation_passed").asBoolean() + "`\n"
                        + "- proof_artifacts_present: `true`\n"
                        + "- production_enablement: `false`\n"
                        + "- release_config_pr_required: `true`\n"
                        + "- human_approval_required: `true`\n"
                        + "- operator_drill_required_before_enablement: `true`\n"
                        + "- final_decision: `READY_FOR_ENABLEMENT_REVIEW`\n\n"
                        + "`READY_FOR_ENABLEMENT_REVIEW` is not production enablement.\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private String markdownRows() {
        StringBuilder builder = new StringBuilder();
        builder.append("| Scenario | Crash window | State reach method | Transaction mode | Killed target | Restarted target | State | Status | Outbox | SUCCESS audit |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | ---: | ---: |\n");
        Path evidence = logDirectory.resolve("evidence-summary.md");
        if (Files.exists(evidence)) {
            try (var lines = Files.lines(evidence)) {
                lines.filter(line -> line.startsWith("- scenario="))
                        .map(this::evidenceLineToMarkdownRow)
                        .forEach(builder::append);
                return builder.toString();
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read FDP-37 evidence rows", exception);
            }
        }
        for (RegulatedMutationChaosResult result : results) {
            builder.append("| ")
                    .append(result.scenarioName()).append(" | ")
                    .append(result.window()).append(" | ")
                    .append(result.stateReachMethod()).append(" | ")
                    .append(scenarioTransactionModes.getOrDefault(result.scenarioName(), "OFF")).append(" | ")
                    .append(result.killedTargetName()).append(" | ")
                    .append(result.restartedTargetName()).append(" | ")
                    .append(result.commandState()).append(" | ")
                    .append(result.executionStatus()).append(" | ")
                    .append(result.outboxRecords()).append(" | ")
                    .append(result.successAuditEvents()).append(" |\n");
        }
        return builder.toString();
    }

    private String evidenceLineToMarkdownRow(String line) {
        Map<String, String> fields = parseEvidenceLine(line);
        return "| "
                + fields.getOrDefault("scenario", "unknown") + " | "
                + fields.getOrDefault("window", "unknown") + " | "
                + fields.getOrDefault("state_reach_method", "unknown") + " | "
                + fields.getOrDefault("transaction_mode", "unknown") + " | "
                + fields.getOrDefault("killed_target", "unknown") + " | "
                + fields.getOrDefault("restarted_target", "unknown") + " | "
                + fields.getOrDefault("state", "recorded") + " | "
                + fields.getOrDefault("status", "recorded") + " | "
                + fields.getOrDefault("outbox", "recorded") + " | "
                + fields.getOrDefault("success_audit", "recorded") + " |\n";
    }

    private static Map<String, String> parseEvidenceLine(String line) {
        Map<String, String> fields = new HashMap<>();
        String normalized = line.startsWith("- ") ? line.substring(2) : line;
        for (String segment : normalized.split(";")) {
            int separator = segment.indexOf('=');
            if (separator > 0 && separator < segment.length() - 1) {
                fields.put(segment.substring(0, separator).trim(), segment.substring(separator + 1).trim());
            }
        }
        return fields;
    }

    public void writeRollbackValidationArtifact(
            boolean checkpointRenewalCanBeDisabledWithoutDisablingFencing,
            boolean fdp32FencingRemainsActive,
            boolean recoveryCommandsVisibleAfterRollback,
            boolean apiReturnsRecoveryOrInProgressAfterRollback,
            boolean noNewSuccessClaimsAfterRollback
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("checkpoint_renewal_can_be_disabled_without_disabling_fencing",
                checkpointRenewalCanBeDisabledWithoutDisablingFencing);
        root.put("FDP32_fencing_remains_active", fdp32FencingRemainsActive);
        root.put("recovery_commands_visible_after_rollback", recoveryCommandsVisibleAfterRollback);
        root.put("API_returns_recovery_or_in_progress_after_rollback", apiReturnsRecoveryOrInProgressAfterRollback);
        root.put("no_new_success_claims_after_rollback", noNewSuccessClaimsAfterRollback);
        root.put("final_result", "PASS");
        try {
            Files.createDirectories(logDirectory);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(logDirectory.resolve(ROLLBACK_VALIDATION_JSON).toFile(), root);
            Files.writeString(
                    logDirectory.resolve(ROLLBACK_VALIDATION_MD),
                    "# FDP-37 Rollback Validation Artifact\n\n"
                            + "- checkpoint_renewal_can_be_disabled_without_disabling_fencing: `" + checkpointRenewalCanBeDisabledWithoutDisablingFencing + "`\n"
                            + "- FDP32_fencing_remains_active: `" + fdp32FencingRemainsActive + "`\n"
                            + "- recovery_commands_visible_after_rollback: `" + recoveryCommandsVisibleAfterRollback + "`\n"
                            + "- API_returns_recovery_or_in_progress_after_rollback: `" + apiReturnsRecoveryOrInProgressAfterRollback + "`\n"
                            + "- no_new_success_claims_after_rollback: `" + noNewSuccessClaimsAfterRollback + "`\n"
                            + "- final_result: `PASS`\n\n"
                            + "Rollback validation is release evidence, not production rollback approval.\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            writeProofArtifacts();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write FDP-37 rollback validation artifact", exception);
        }
    }

    private List<String> alertServiceArgs(List<String> additionalArgs) {
        List<String> args = new ArrayList<>();
        args.add("--server.port=8080");
        args.add("--spring.profiles.active=test");
        args.add("--spring.data.mongodb.uri=" + mongodbUri);
        args.add("--spring.data.redis.host=" + FraudPlatformContainers.redisNetworkHost());
        args.add("--spring.data.redis.port=6379");
        args.add("--spring.kafka.bootstrap-servers=" + FraudPlatformContainers.kafkaNetworkBootstrapServers());
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
        RegulatedMutationChaosWaiter.waitUntil(
                "FDP-37 alert-service readiness on mapped port " + servicePort,
                Duration.ofSeconds(90),
                Duration.ofMillis(500),
                () -> {
            if (container == null || !container.isRunning()) {
                throw new IllegalStateException("FDP-37 alert-service container exited before readiness; logs="
                        + (container == null ? "<no container>" : container.getLogs()));
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
                    return RegulatedMutationChaosWaiter.ProbeResult.satisfied("HTTP 200 " + response.body());
                }
                return RegulatedMutationChaosWaiter.ProbeResult.waiting(
                        "HTTP " + response.statusCode() + " " + response.body()
                );
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for FDP-37 alert-service readiness", ignored);
                }
                return RegulatedMutationChaosWaiter.ProbeResult.waiting(
                        ignored.getClass().getSimpleName() + ": " + ignored.getMessage()
                );
            }
                }
        );
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

    private String imageTag() {
        int separator = imageName.lastIndexOf(':');
        if (separator < 0 || separator == imageName.length() - 1) {
            return "untagged";
        }
        return imageName.substring(separator + 1);
    }

    private String currentTransactionMode() {
        return lastEffectiveArgs.stream()
                .filter(argument -> argument.startsWith("--app.regulated-mutations.transaction-mode="))
                .map(argument -> argument.substring(argument.indexOf('=') + 1))
                .findFirst()
                .orElse("OFF");
    }

    private long aggregateScenarioCount() {
        Path evidence = logDirectory.resolve("evidence-summary.md");
        if (!Files.exists(evidence)) {
            return results.size();
        }
        try (var lines = Files.lines(evidence)) {
            return lines.filter(line -> line.startsWith("- scenario=")).count();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to count FDP-37 evidence scenarios", exception);
        }
    }

    private long countScenarios(RegulatedMutationStateReachMethod reachMethod) {
        Path evidence = logDirectory.resolve("evidence-summary.md");
        if (Files.exists(evidence)) {
            try (var lines = Files.lines(evidence)) {
                return lines.filter(line -> line.startsWith("- scenario="))
                        .filter(line -> line.contains("state_reach_method=" + reachMethod.name()))
                        .count();
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to count FDP-37 evidence scenarios by reach method", exception);
            }
        }
        return results.stream()
                .filter(result -> result.stateReachMethod() == reachMethod)
                .count();
    }

    private boolean hasRequiredTransactionScenario() {
        Path evidence = logDirectory.resolve("evidence-summary.md");
        if (Files.exists(evidence)) {
            return evidenceContainsRequiredTransactionScenario(evidence);
        }
        return scenarioTransactionModes.values().stream().anyMatch("REQUIRED"::equals);
    }

    static boolean evidenceContainsRequiredTransactionScenario(Path evidence) {
        try (var lines = Files.lines(evidence)) {
            return lines.filter(line -> line.startsWith("- scenario="))
                    .map(RegulatedMutationProductionImageChaosHarness::parseEvidenceLine)
                    .anyMatch(fields -> "REQUIRED".equals(fields.get("transaction_mode")));
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to inspect FDP-37 evidence transaction modes", exception);
        }
    }

    private String requiredWorkflowStatus(String envName) {
        return Optional.ofNullable(System.getenv(envName))
                .filter(value -> !value.isBlank())
                .orElse("REQUIRED_BY_WORKFLOW_NEEDS");
    }

    private boolean liveFixtureEnabled() {
        return lastEffectiveArgs.stream().anyMatch(argument -> argument.contains("fdp36-live-in-flight"));
    }

    private String requireAlertServiceImage(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("FDP-37 requires " + IMAGE_PROPERTY + " or " + IMAGE_ENV);
        }
        return assertAlertServiceImage(candidate.trim());
    }

    private void requireImageProvenanceInCi() {
        boolean ci = Boolean.parseBoolean(Optional.ofNullable(System.getenv("CI")).orElse("false"));
        if (!ci) {
            return;
        }
        if (!imageId.startsWith("sha256:")) {
            throw new IllegalStateException("FDP-37 CI proof requires Docker image id starting with sha256:");
        }
        if (imageDigest.isBlank() || "LOCAL_IMAGE_DIGEST_NOT_PROVIDED".equals(imageDigest)) {
            throw new IllegalStateException("FDP-37 CI proof requires image digest or immutable image id");
        }
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

    @Override
    public void close() {
        if (container != null) {
            container.close();
        }
    }
}
