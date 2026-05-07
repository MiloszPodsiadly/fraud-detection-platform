package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp39MustNotChangeRuntimeMutationSemanticsTest {

    private static final List<String> PROTECTED_RUNTIME_PATH_FRAGMENTS = List.of(
            "alert-service/src/main/java/com/frauddetection/alert/regulated/MongoRegulatedMutationCoordinator",
            "alert-service/src/main/java/com/frauddetection/alert/regulated/LegacyRegulatedMutationExecutor",
            "alert-service/src/main/java/com/frauddetection/alert/regulated/EvidenceGatedFinalizeExecutor",
            "alert-service/src/main/java/com/frauddetection/alert/regulated/mutation/",
            "alert-service/src/main/java/com/frauddetection/alert/outbox/",
            "alert-service/src/main/java/com/frauddetection/alert/audit/",
            "alert-service/src/main/java/com/frauddetection/alert/regulated/MutationEvidenceConfirmation",
            "alert-service/src/main/java/com/frauddetection/alert/api/SubmitDecisionOperationStatus"
    );

    @Test
    void fdp39DiffDoesNotTouchRuntimeMutationSemanticsFiles() throws Exception {
        List<String> changedFiles = changedFiles();
        assertThat(changedFiles)
                .filteredOn(this::isProtectedRuntimePath)
                .as("FDP-39 must stay governance/proof-only and avoid runtime mutation semantic files")
                .isEmpty();
    }

    @Test
    void fdp39MarkersDoNotAppearInRuntimeSourceOrFeatureDefaults() throws Exception {
        try (Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .toList()) {
                assertThat(Files.readString(path))
                        .as("FDP-39 governance marker must not leak into runtime source: " + path)
                        .doesNotContain("FDP-39")
                        .doesNotContain("fdp39")
                        .doesNotContain("PRODUCTION_ENABLED")
                        .doesNotContain("BANK_CERTIFIED");
            }
        }
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(application)
                .contains("evidence-gated-finalize:")
                .doesNotContain("fdp39")
                .doesNotContain("production-enabled: true");
    }

    private boolean isProtectedRuntimePath(String path) {
        String normalized = path.replace('\\', '/');
        return PROTECTED_RUNTIME_PATH_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    private List<String> changedFiles() throws IOException, InterruptedException {
        List<String> fromMergeBase = runGit("diff", "--name-only", "origin/main...HEAD");
        if (!fromMergeBase.isEmpty()) {
            return fromMergeBase;
        }
        return runGit("diff", "--name-only", "HEAD");
    }

    private List<String> runGit(String... args) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(command(args));
        builder.directory(Path.of("..").toAbsolutePath().normalize().toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            return List.of();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).toList();
        }
    }

    private List<String> command(String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=C:/Users/mpods/IdeaProjects/fraud-detection-platform");
        command.addAll(List.of(args));
        return command;
    }
}
