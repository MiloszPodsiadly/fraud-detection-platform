package com.frauddetection.alert.regulated.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.security.auth.DemoAuthHeaders;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class RegulatedMutationAlertServiceProcessChaosHarness implements AutoCloseable {

    static final String ALERT_SERVICE_MAIN_CLASS = "com.frauddetection.alert.AlertServiceApplication";
    static final String ALERT_SERVICE_TARGET_NAME = "alert-service";

    private final MongoTemplate mongoTemplate;
    private final String mongodbUri;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path logDirectory;

    private Process serviceProcess;
    private long killedProcessId = -1;
    private long restartedProcessId = -1;
    private int servicePort;

    public RegulatedMutationAlertServiceProcessChaosHarness(MongoTemplate mongoTemplate, String mongodbUri) {
        this.mongoTemplate = mongoTemplate;
        this.mongodbUri = mongodbUri;
        this.logDirectory = Path.of("target", "fdp36-chaos");
    }

    public RegulatedMutationChaosResult run(RegulatedMutationChaosScenario scenario) {
        startAlertService("before-kill-" + scenario.name());
        scenario.seedDurableState().accept(mongoTemplate);
        killAlertServiceAbruptly();
        restartAlertService("after-restart-" + scenario.name());
        JsonNode inspection = inspectByCommandId(scenario.commandId());
        return collectEvidence(scenario, inspection, null);
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

    public RegulatedMutationChaosResult collectEvidence(RegulatedMutationChaosScenario scenario) {
        return collectEvidence(scenario, inspectByCommandId(scenario.commandId()), null);
    }

    public RegulatedMutationChaosResult collectEvidence(
            RegulatedMutationChaosScenario scenario,
            RegulatedMutationProofLevel proofLevel
    ) {
        return collectEvidence(scenario, inspectByCommandId(scenario.commandId()), null, proofLevel);
    }

    public RegulatedMutationChaosResult collectEvidence(
            RegulatedMutationChaosScenario scenario,
            JsonNode inspectionResponse,
            JsonNode recoveryResponse
    ) {
        return collectEvidence(
                scenario,
                inspectionResponse,
                recoveryResponse,
                RegulatedMutationProofLevel.REAL_ALERT_SERVICE_KILL
        );
    }

    public RegulatedMutationChaosResult collectEvidence(
            RegulatedMutationChaosScenario scenario,
            JsonNode inspectionResponse,
            JsonNode recoveryResponse,
            RegulatedMutationProofLevel proofLevel
    ) {
        RegulatedMutationCommandDocument command = mongoTemplate.findById(
                scenario.commandId(),
                RegulatedMutationCommandDocument.class
        );
        if (command == null) {
            throw new IllegalStateException("FDP-36 scenario command is missing: " + scenario.commandId());
        }
        Optional<AlertDocument> alert = Optional.ofNullable(mongoTemplate.findById(command.getResourceId(), AlertDocument.class));
        RegulatedMutationChaosResult result = new RegulatedMutationChaosResult(
                scenario.name(),
                scenario.window(),
                proofLevel,
                String.valueOf(killedProcessId),
                String.valueOf(restartedProcessId),
                ALERT_SERVICE_TARGET_NAME + ":" + ALERT_SERVICE_MAIN_CLASS,
                ALERT_SERVICE_TARGET_NAME + ":" + ALERT_SERVICE_MAIN_CLASS,
                killedProcessId > 0,
                restartedProcessId > 0,
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
        appendEvidenceSummary(result);
        return result;
    }

    public void startAlertService(String logName) {
        startAlertService(logName, List.of());
    }

    public void startAlertService(String logName, List<String> additionalArgs) {
        assertNoRunningProcess();
        servicePort = freePort();
        try {
            Files.createDirectories(logDirectory);
            serviceProcess = new ProcessBuilder(alertServiceCommand(additionalArgs))
                    .directory(Path.of("").toAbsolutePath().toFile())
                    .redirectOutput(logDirectory.resolve(logName + "-stdout.log").toFile())
                    .redirectError(logDirectory.resolve(logName + "-stderr.log").toFile())
                    .start();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to start real alert-service JVM for FDP-36 chaos proof", exception);
        }
        awaitReadiness();
    }

    public void killAlertServiceAbruptly() {
        if (serviceProcess == null || !serviceProcess.isAlive()) {
            throw new IllegalStateException("FDP-36 alert-service process was not running");
        }
        killedProcessId = serviceProcess.pid();
        serviceProcess.destroyForcibly();
        try {
            serviceProcess.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while killing FDP-36 alert-service process", exception);
        } finally {
            serviceProcess = null;
        }
    }

    public void restartAlertService(String logName) {
        restartAlertService(logName, List.of());
    }

    public void restartAlertService(String logName, List<String> additionalArgs) {
        startAlertService(logName, additionalArgs);
        restartedProcessId = serviceProcess.pid();
        if (restartedProcessId == killedProcessId) {
            throw new IllegalStateException("FDP-36 restarted alert-service process reused killed pid");
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

    public String evidenceSummary() {
        return "killed_target=" + ALERT_SERVICE_TARGET_NAME
                + ";killed_process=" + killedProcessId
                + ";restarted_target=" + ALERT_SERVICE_TARGET_NAME
                + ";restarted_process=" + restartedProcessId
                + ";proof_levels=" + RegulatedMutationProofLevel.REAL_ALERT_SERVICE_KILL
                + "," + RegulatedMutationProofLevel.REAL_ALERT_SERVICE_RESTART_API_PROOF;
    }

    private void appendEvidenceSummary(RegulatedMutationChaosResult result) {
        try {
            Files.createDirectories(logDirectory);
            Files.writeString(
                    logDirectory.resolve("evidence-summary.md"),
                    "- scenario=" + result.scenarioName()
                            + "; window=" + result.window()
                            + "; proof_level=" + result.proofLevel()
                            + "; killed_target=" + result.killedTargetName()
                            + "; killed_process=" + result.killedTargetId()
                            + "; restarted_target=" + result.restartedTargetName()
                            + "; restarted_process=" + result.restartedTargetId()
                            + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write FDP-36 chaos evidence summary", exception);
        }
    }

    private List<String> alertServiceCommand(List<String> additionalArgs) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(testClasspath());
        command.add(ALERT_SERVICE_MAIN_CLASS);
        command.add("--server.port=" + servicePort);
        command.add("--spring.profiles.active=test");
        command.add("--spring.data.mongodb.uri=" + mongodbUri);
        command.add("--spring.data.redis.host=" + FraudPlatformContainers.redis().getHost());
        command.add("--spring.data.redis.port=" + FraudPlatformContainers.redis().getMappedPort(6379));
        command.add("--spring.kafka.bootstrap-servers=" + FraudPlatformContainers.kafka().getBootstrapServers());
        command.add("--app.security.demo-auth.enabled=true");
        command.add("--app.outbox.publisher.enabled=false");
        command.add("--app.evidence-confirmation.enabled=false");
        command.add("--app.regulated-mutation.recovery.scheduler.enabled=false");
        command.add("--app.kafka.topics.transaction-scored=fdp36.transactions.scored");
        command.add("--app.kafka.topics.fraud-alerts=fdp36.fraud.alerts");
        command.add("--app.kafka.topics.fraud-decisions=fdp36.fraud.decisions");
        command.add("--app.kafka.topics.transactions-dead-letter=fdp36.transactions.dead-letter");
        command.add("--logging.level.root=WARN");
        command.addAll(additionalArgs);
        return command;
    }

    private JsonNode requestJson(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("FDP-36 restarted alert-service API returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new UncheckedIOException("FDP-36 restarted alert-service API call failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during FDP-36 restarted alert-service API call", exception);
        }
    }

    private void awaitReadiness() {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (serviceProcess == null || !serviceProcess.isAlive()) {
                throw new IllegalStateException("FDP-36 alert-service process exited before readiness");
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
                    throw new IllegalStateException("Interrupted while waiting for alert-service readiness", ignored);
                }
            }
            sleep();
        }
        throw new IllegalStateException("FDP-36 alert-service process did not become ready on port " + servicePort);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + servicePort + path);
    }

    private String[] demoHeaders() {
        return new String[]{
                DemoAuthHeaders.USER_ID, "fdp36-operator",
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
            throw new UncheckedIOException("Unable to allocate FDP-36 alert-service test port", exception);
        }
    }

    private void assertNoRunningProcess() {
        if (serviceProcess != null && serviceProcess.isAlive()) {
            throw new IllegalStateException("FDP-36 alert-service process is already running");
        }
    }

    private String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String testClasspath() {
        return Optional.ofNullable(System.getProperty("surefire.test.class.path"))
                .orElseGet(() -> System.getProperty("java.class.path"));
    }

    private void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for alert-service readiness", exception);
        }
    }

    @Override
    public void close() {
        if (serviceProcess != null && serviceProcess.isAlive()) {
            serviceProcess.destroyForcibly();
        }
    }
}
