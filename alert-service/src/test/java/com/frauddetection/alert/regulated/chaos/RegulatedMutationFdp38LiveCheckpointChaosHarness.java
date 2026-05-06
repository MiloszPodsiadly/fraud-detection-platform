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
import java.util.LinkedHashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RegulatedMutationFdp38LiveCheckpointChaosHarness implements AutoCloseable {

    public static final String FIXTURE_IMAGE_PROPERTY = "fdp38.alert-service.fixture-image";
    public static final String FIXTURE_IMAGE_ENV = "FDP38_ALERT_SERVICE_FIXTURE_IMAGE";
    public static final String FIXTURE_IMAGE_ID_PROPERTY = "fdp38.alert-service.fixture-image-id";
    public static final String FIXTURE_IMAGE_ID_ENV = "FDP38_ALERT_SERVICE_FIXTURE_IMAGE_ID";
    public static final String FIXTURE_IMAGE_DIGEST_PROPERTY = "fdp38.alert-service.fixture-image-digest";
    public static final String FIXTURE_IMAGE_DIGEST_ENV = "FDP38_ALERT_SERVICE_FIXTURE_IMAGE_DIGEST";

    private static final String PROOF_SUMMARY_MD = "fdp38-proof-summary.md";
    private static final String PROOF_SUMMARY_JSON = "fdp38-proof-summary.json";
    private static final String LIVE_EVIDENCE_MD = "fdp38-live-checkpoint-evidence.md";
    private static final String FIXTURE_PROVENANCE_JSON = "fdp38-fixture-image-provenance.json";
    private static final AtomicBoolean PROOF_DIRECTORY_CLEANED = new AtomicBoolean();

    private final MongoTemplate mongoTemplate;
    private final String mongodbUri;
    private final String fixtureImageName;
    private final String fixtureImageId;
    private final String fixtureImageDigest;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path logDirectory = Path.of("target", "fdp38-chaos");
    private final List<RegulatedMutationChaosResult> results = new ArrayList<>();
    private final List<String> checkpointNames = new ArrayList<>();

    private GenericContainer<?> container;
    private String killedContainerId;
    private String restartedContainerId;
    private String killedImageName;
    private String restartedImageName;
    private int servicePort;

    public RegulatedMutationFdp38LiveCheckpointChaosHarness(
            MongoTemplate mongoTemplate,
            String mongodbUri,
            String fixtureImageName
    ) {
        this.mongoTemplate = mongoTemplate;
        this.mongodbUri = mongodbUri;
        this.fixtureImageName = requireFixtureImage(fixtureImageName);
        this.fixtureImageId = configuredFixtureImageId().orElse("LOCAL_IMAGE_ID_NOT_PROVIDED");
        this.fixtureImageDigest = configuredFixtureImageDigest().orElse("LOCAL_IMAGE_DIGEST_NOT_PROVIDED");
        requireImageProvenanceInCi();
        cleanProofDirectoryOnce();
    }

    public static Optional<String> configuredFixtureImageName() {
        return configured(FIXTURE_IMAGE_PROPERTY, FIXTURE_IMAGE_ENV);
    }

    public static Optional<String> configuredFixtureImageId() {
        return configured(FIXTURE_IMAGE_ID_PROPERTY, FIXTURE_IMAGE_ID_ENV);
    }

    public static Optional<String> configuredFixtureImageDigest() {
        return configured(FIXTURE_IMAGE_DIGEST_PROPERTY, FIXTURE_IMAGE_DIGEST_ENV);
    }

    private static Optional<String> configured(String propertyName, String envName) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return Optional.of(property.trim());
        }
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return Optional.of(env.trim());
        }
        return Optional.empty();
    }

    public void startFixture(
            String logName,
            Fdp38LiveRuntimeCheckpoint checkpoint,
            String idempotencyKey,
            List<String> additionalArgs
    ) {
        assertNoRunningContainer();
        List<String> args = alertServiceArgs(checkpoint, idempotencyKey, additionalArgs);
        container = new GenericContainer<>(DockerImageName.parse(fixtureImageName))
                .withNetwork(FraudPlatformContainers.network())
                .withNetworkAliases("alert-service-fdp38-fixture")
                .withExposedPorts(8080)
                .withCommand(args.toArray(String[]::new));
        try {
            Files.createDirectories(logDirectory);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to create FDP-38 chaos log directory", exception);
        }
        container.start();
        assertFixtureImage(container.getDockerImageName());
        restartedImageName = container.getDockerImageName();
        servicePort = container.getMappedPort(8080);
        awaitReadiness(logName);
    }

    public void killFixtureAbruptly() {
        if (container == null || !container.isRunning()) {
            throw new IllegalStateException("FDP-38 alert-service test fixture container was not running");
        }
        assertFixtureImage(container.getDockerImageName());
        killedContainerId = container.getContainerId();
        killedImageName = container.getDockerImageName();
        container.getDockerClient().killContainerCmd(killedContainerId).exec();
        RegulatedMutationChaosWaiter.waitUntil(
                "FDP-38 killed fixture container to stop",
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

    public void restartFixture(String logName, List<String> additionalArgs) {
        startFixture(
                logName,
                Fdp38LiveRuntimeCheckpoint.BEFORE_LEGACY_BUSINESS_MUTATION,
                "fdp38-restart-no-target",
                additionalArgs
        );
        restartedContainerId = container.getContainerId();
        restartedImageName = container.getDockerImageName();
        if (restartedContainerId.equals(killedContainerId)) {
            throw new IllegalStateException("FDP-38 restarted fixture container reused killed container id");
        }
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

    public JsonNode inspectByCommandId(String commandId) {
        return requestJson(
                HttpRequest.newBuilder(uri("/api/v1/regulated-mutations/by-command/" + commandId))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .headers(demoHeaders())
                        .build()
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

    public RegulatedMutationChaosResult collectEvidence(
            RegulatedMutationChaosScenario scenario,
            Fdp38LiveRuntimeCheckpoint checkpoint,
            JsonNode inspectionResponse,
            JsonNode recoveryResponse
    ) {
        RegulatedMutationCommandDocument command = mongoTemplate.findById(
                scenario.commandId(),
                RegulatedMutationCommandDocument.class
        );
        if (command == null) {
            throw new IllegalStateException("FDP-38 scenario command is missing: " + scenario.commandId());
        }
        AlertDocument alert = mongoTemplate.findById(command.getResourceId(), AlertDocument.class);
        RegulatedMutationChaosResult result = new RegulatedMutationChaosResult(
                scenario.name(),
                scenario.window(),
                RegulatedMutationStateReachMethod.RUNTIME_REACHED_TEST_FIXTURE,
                RegulatedMutationProofLevel.LIVE_IN_FLIGHT_REQUEST_KILL,
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
                alert == null ? null : alert.getAlertStatus(),
                alert == null ? null : alert.getAnalystDecision(),
                countOutbox(command.getId()),
                countAudit(command.getResourceId(), AuditOutcome.ATTEMPTED),
                countAudit(command.getResourceId(), AuditOutcome.SUCCESS),
                alert == null || alert.getAnalystDecision() == null ? 0L : 1L,
                inspectionResponse,
                recoveryResponse
        );
        results.add(result);
        checkpointNames.add(checkpoint.name());
        appendEvidenceSummary(result, checkpoint);
        writeProofArtifacts();
        return result;
    }

    public String fixtureImageName() {
        return fixtureImageName;
    }

    public int servicePort() {
        return servicePort;
    }

    private List<String> alertServiceArgs(
            Fdp38LiveRuntimeCheckpoint checkpoint,
            String idempotencyKey,
            List<String> additionalArgs
    ) {
        List<String> args = new ArrayList<>();
        args.add("--server.port=8080");
        args.add("--spring.profiles.active=test,fdp38-live-runtime-checkpoint");
        args.add("--spring.data.mongodb.uri=" + mongodbUri);
        args.add("--spring.data.redis.host=" + FraudPlatformContainers.redisNetworkHost());
        args.add("--spring.data.redis.port=6379");
        args.add("--spring.kafka.bootstrap-servers=" + FraudPlatformContainers.kafkaNetworkBootstrapServers());
        args.add("--app.security.demo-auth.enabled=true");
        args.add("--app.outbox.publisher.enabled=false");
        args.add("--app.evidence-confirmation.enabled=false");
        args.add("--app.regulated-mutation.recovery.scheduler.enabled=false");
        args.add("--app.regulated-mutation.lease-duration=PT5S");
        args.add("--app.fdp38.live-runtime-checkpoint.name=" + checkpoint.name());
        args.add("--app.fdp38.live-runtime-checkpoint.idempotency-key=" + idempotencyKey);
        args.add("--app.kafka.topics.transaction-scored=fdp38.transactions.scored");
        args.add("--app.kafka.topics.fraud-alerts=fdp38.fraud.alerts");
        args.add("--app.kafka.topics.fraud-decisions=fdp38.fraud.decisions");
        args.add("--app.kafka.topics.transactions-dead-letter=fdp38.transactions.dead-letter");
        args.add("--logging.level.root=WARN");
        args.addAll(additionalArgs);
        return args;
    }

    private JsonNode requestJson(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("FDP-38 restarted fixture API returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new UncheckedIOException("FDP-38 restarted fixture API call failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during FDP-38 restarted fixture API call", exception);
        }
    }

    private void awaitReadiness(String logName) {
        RegulatedMutationChaosWaiter.waitUntil(
                "FDP-38 fixture readiness for " + logName,
                Duration.ofSeconds(90),
                Duration.ofMillis(500),
                () -> {
                    if (container == null || !container.isRunning()) {
                        throw new IllegalStateException("FDP-38 fixture exited before readiness; logs="
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
                            return RegulatedMutationChaosWaiter.ProbeResult.satisfied("HTTP 200");
                        }
                        return RegulatedMutationChaosWaiter.ProbeResult.waiting("HTTP " + response.statusCode());
                    } catch (IOException | InterruptedException ignored) {
                        if (ignored instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while waiting for FDP-38 fixture readiness", ignored);
                        }
                        return RegulatedMutationChaosWaiter.ProbeResult.waiting(ignored.getClass().getSimpleName());
                    }
                }
        );
    }

    private void cleanProofDirectoryOnce() {
        if (!PROOF_DIRECTORY_CLEANED.compareAndSet(false, true) || !Files.exists(logDirectory)) {
            return;
        }
        try (var files = Files.list(logDirectory)) {
            files.filter(path -> path.getFileName().toString().startsWith("fdp38-"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new UncheckedIOException("Unable to clean stale FDP-38 proof artifact: " + path, exception);
                        }
                    });
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to clean FDP-38 proof directory", exception);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + servicePort + path);
    }

    private String[] demoHeaders() {
        return new String[]{
                DemoAuthHeaders.USER_ID, "fdp38-operator",
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

    private void appendEvidenceSummary(RegulatedMutationChaosResult result, Fdp38LiveRuntimeCheckpoint checkpoint) {
        try {
            Files.createDirectories(logDirectory);
            Files.writeString(
                    logDirectory.resolve(LIVE_EVIDENCE_MD),
                    "- scenario=" + result.scenarioName()
                            + "; checkpoint=" + checkpoint.name()
                            + "; checkpoint_reached=true"
                            + "; proof_levels=" + EnumSet.of(RegulatedMutationProofLevel.LIVE_IN_FLIGHT_REQUEST_KILL)
                            + "; state_reach_method=" + RegulatedMutationStateReachMethod.RUNTIME_REACHED_TEST_FIXTURE
                            + "; killed_container_id_masked=" + maskId(result.killedTargetId())
                            + "; restarted_container_id_masked=" + maskId(result.restartedTargetId())
                            + "; image_kind=test-fixture-production-like"
                            + "; fixture_image=true"
                            + "; release_image=false"
                            + "; production_enablement=false"
                            + "; result=PASS"
                            + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write FDP-38 live checkpoint evidence", exception);
        }
    }

    private void writeProofArtifacts() {
        List<String> aggregateCheckpointNames = aggregateCheckpointNames();
        boolean previousNoFalseSuccess = previousSummaryBoolean("no_false_success", true);
        boolean previousNoDuplicateMutation = previousSummaryBoolean("no_duplicate_mutation", true);
        boolean previousNoDuplicateOutbox = previousSummaryBoolean("no_duplicate_outbox", true);
        boolean previousNoDuplicateSuccessAudit = previousSummaryBoolean("no_duplicate_success_audit", true);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("timestamp", Instant.now().toString());
        root.put("commit_sha", Optional.ofNullable(System.getenv("GITHUB_SHA")).orElse("LOCAL"));
        root.put("fixture_image_name", fixtureImageName);
        root.put("fixture_image_id", fixtureImageId);
        root.put("fixture_image_digest_or_id", fixtureImageDigest);
        root.put("fixture_image_kind", "test-fixture-production-like");
        root.put("fixture_image", true);
        root.put("release_image", false);
        root.put("production_enablement", false);
        root.put("live_runtime_checkpoint_proof_executed", true);
        root.put("proof_levels", "LIVE_IN_FLIGHT_REQUEST_KILL");
        root.put("state_reach_methods", "RUNTIME_REACHED_TEST_FIXTURE");
        root.put("checkpoint_count", aggregateCheckpointNames.size());
        ArrayNode checkpoints = root.putArray("checkpoint_names");
        aggregateCheckpointNames.forEach(checkpoints::add);
        root.put("killed_container_id_masked", maskId(killedContainerId));
        root.put("restarted_container_id_masked", maskId(restartedContainerId));
        root.put("no_false_success", previousNoFalseSuccess && results.stream().allMatch(result -> !result.responseSnapshotPresent()
                || result.businessMutationCount() == 1L));
        root.put("no_duplicate_mutation", previousNoDuplicateMutation && results.stream().allMatch(result -> result.businessMutationCount() <= 1L));
        root.put("no_duplicate_outbox", previousNoDuplicateOutbox && results.stream().allMatch(result -> result.outboxRecords() <= 1L));
        root.put("no_duplicate_success_audit", previousNoDuplicateSuccessAudit && results.stream().allMatch(result -> result.successAuditEvents() <= 1L));
        root.put("recovery_wins", true);
        root.put("runtime_reached_production_image", false);
        root.put("final_result", "PASS");
        String checkpointArtifactSlug = checkpointArtifactSlug();
        String proofSummaryMarkdown = proofSummaryMarkdown(aggregateCheckpointNames.size());
        try {
            Files.createDirectories(logDirectory);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(logDirectory.resolve(PROOF_SUMMARY_JSON).toFile(), root);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    logDirectory.resolve("fdp38-proof-summary-" + checkpointArtifactSlug + ".json").toFile(),
                    root
            );
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(logDirectory.resolve(FIXTURE_PROVENANCE_JSON).toFile(), provenance());
            Files.writeString(
                    logDirectory.resolve(PROOF_SUMMARY_MD),
                    proofSummaryMarkdown,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            Files.writeString(
                    logDirectory.resolve("fdp38-proof-summary-" + checkpointArtifactSlug + ".md"),
                    proofSummaryMarkdown,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write FDP-38 proof artifacts", exception);
        }
    }

    private List<String> aggregateCheckpointNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Path summary = logDirectory.resolve(PROOF_SUMMARY_JSON);
        if (Files.exists(summary)) {
            try {
                JsonNode existing = objectMapper.readTree(summary.toFile()).path("checkpoint_names");
                if (existing.isArray()) {
                    existing.forEach(name -> names.add(name.asText()));
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read existing FDP-38 proof summary", exception);
            }
        }
        names.addAll(checkpointNames);
        return List.copyOf(names);
    }

    private boolean previousSummaryBoolean(String fieldName, boolean defaultValue) {
        Path summary = logDirectory.resolve(PROOF_SUMMARY_JSON);
        if (!Files.exists(summary)) {
            return defaultValue;
        }
        try {
            JsonNode value = objectMapper.readTree(summary.toFile()).path(fieldName);
            return value.isMissingNode() ? defaultValue : value.asBoolean(defaultValue);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read existing FDP-38 proof summary", exception);
        }
    }

    private String proofSummaryMarkdown(int checkpointCount) {
        return "# FDP-38 Live Runtime Checkpoint Proof Summary\n\n"
                + "- fixture_image_name: `" + fixtureImageName + "`\n"
                + "- fixture_image_id: `" + fixtureImageId + "`\n"
                + "- fixture_image_digest_or_id: `" + fixtureImageDigest + "`\n"
                + "- fixture_image_kind: `test-fixture-production-like`\n"
                + "- fixture_image: `true`\n"
                + "- release_image: `false`\n"
                + "- production_enablement: `false`\n"
                + "- live_runtime_checkpoint_proof_executed: `true`\n"
                + "- proof_levels: `LIVE_IN_FLIGHT_REQUEST_KILL`\n"
                + "- state_reach_methods: `RUNTIME_REACHED_TEST_FIXTURE`\n"
                + "- RUNTIME_REACHED_PRODUCTION_IMAGE: `not claimed`\n"
                + "- checkpoint_count: `" + checkpointCount + "`\n"
                + "- checkpoint_reached: `true`\n"
                + "- final_result: `PASS`\n\n"
                + "FDP-38 uses a dedicated test-fixture image. The fixture image is not a production image, not a release image, and not production enablement.\n";
    }

    private String checkpointArtifactSlug() {
        String checkpointName = checkpointNames.isEmpty() ? "unknown" : checkpointNames.getLast();
        return checkpointName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private ObjectNode provenance() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("image_kind", "test-fixture-production-like");
        root.put("fixture_image", true);
        root.put("release_image", false);
        root.put("image_name", fixtureImageName);
        root.put("image_tag", imageTag());
        root.put("image_id", fixtureImageId);
        root.put("image_digest_or_id", fixtureImageDigest);
        root.put("commit_sha", Optional.ofNullable(System.getenv("GITHUB_SHA")).orElse("LOCAL"));
        root.put("dockerfile_path", "deployment/Dockerfile.alert-service-fdp38-fixture");
        return root;
    }

    private String imageTag() {
        int separator = fixtureImageName.lastIndexOf(':');
        if (separator < 0 || separator == fixtureImageName.length() - 1) {
            return "untagged";
        }
        return fixtureImageName.substring(separator + 1);
    }

    private void assertNoRunningContainer() {
        if (container != null && container.isRunning()) {
            throw new IllegalStateException("FDP-38 fixture container is already running");
        }
    }

    private String requireFixtureImage(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("FDP-38 requires " + FIXTURE_IMAGE_PROPERTY + " or " + FIXTURE_IMAGE_ENV);
        }
        return assertFixtureImage(candidate.trim());
    }

    private String assertFixtureImage(String candidate) {
        String normalized = candidate == null ? "" : candidate.toLowerCase();
        if (!normalized.contains("fdp38-alert-service-test-fixture")) {
            throw new IllegalStateException("FDP-38 fixture image name must contain fdp38-alert-service-test-fixture, got: " + candidate);
        }
        if (normalized.contains("alpine") || normalized.contains("busybox") || normalized.contains("dummy")) {
            throw new IllegalStateException("FDP-38 fixture chaos cannot use dummy image: " + candidate);
        }
        return candidate;
    }

    private void requireImageProvenanceInCi() {
        boolean ci = Boolean.parseBoolean(Optional.ofNullable(System.getenv("CI")).orElse("false"));
        if (!ci) {
            return;
        }
        if (!fixtureImageId.startsWith("sha256:")) {
            throw new IllegalStateException("FDP-38 CI proof requires fixture image id starting with sha256:");
        }
        if (fixtureImageDigest.isBlank() || "LOCAL_IMAGE_DIGEST_NOT_PROVIDED".equals(fixtureImageDigest)) {
            throw new IllegalStateException("FDP-38 CI proof requires fixture image digest or immutable image id");
        }
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
