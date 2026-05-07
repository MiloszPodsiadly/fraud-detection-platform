package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            "alert-service/src/main/java/com/frauddetection/alert/api/SubmitDecisionOperationStatus",
            "alert-service/src/main/resources/application.yml",
            "common-events/src/main/java/com/frauddetection/common/events/"
    );

    private static final Path OUTPUT_DIR = Path.of("target", "fdp39-governance");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fdp39DiffDoesNotTouchRuntimeMutationSemanticsFiles() throws Exception {
        DiffResult diff = changedFiles();
        List<String> protectedRuntimeFiles = diff.changedFiles().stream()
                .filter(this::isProtectedRuntimePath)
                .toList();
        writeRuntimeImmutabilityArtifact(diff, protectedRuntimeFiles);
        assertThat(diff.diffComputed())
                .as("FDP-39 runtime immutability diff must be computed")
                .isTrue();
        if (Boolean.getBoolean("fdp39.ci-mode")) {
            assertThat(diff.changedFiles())
                    .as("FDP-39 CI diff should not be empty for this governance branch")
                    .isNotEmpty();
        }
        assertThat(protectedRuntimeFiles)
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

    private DiffResult changedFiles() throws IOException, InterruptedException {
        boolean ciMode = Boolean.getBoolean("fdp39.ci-mode");
        String eventName = propertyOrEnv("fdp39.event-name", "GITHUB_EVENT_NAME", "local");
        String headSha = propertyOrEnv("fdp39.head-sha", "GITHUB_SHA", "HEAD");
        String baseRef = baseRef();

        if (ciMode && "push".equals(eventName)) {
            String baseSha = propertyOrEnv("fdp39.base-sha", "GITHUB_EVENT_BEFORE", "");
            assertThat(baseSha)
                    .as("FDP-39 push CI must provide github.event.before for audited diff provenance")
                    .isNotBlank();
            assertThat(isZeroSha(baseSha))
                    .as("FDP-39 push CI cannot audit runtime immutability from an all-zero before SHA")
                    .isFalse();
            return diffBetween(eventName, "push-before-to-head", baseRef, baseSha, headSha);
        }

        ProcessResult verifyOrigin = runGit("rev-parse", "--verify", "origin/" + baseRef);
        if (ciMode && !verifyOrigin.success()) {
            ProcessResult fetch = runGit("fetch", "--no-tags", "origin", baseRef);
            assertThat(fetch.success())
                    .as("FDP-39 CI failed to fetch origin/" + baseRef + ": " + fetch.stderr())
                    .isTrue();
        }
        ProcessResult mergeBase = runGit("merge-base", headSha, "origin/" + baseRef);
        if (!mergeBase.success() && ciMode) {
            throw new AssertionError("FDP-39 CI failed to compute merge-base for origin/" + baseRef + ": " + mergeBase.stderr());
        }
        if (mergeBase.success() && !mergeBase.stdout().isEmpty()) {
            String baseSha = mergeBase.stdout().getFirst();
            return diffBetween(eventName, "pull-request-merge-base-to-head", baseRef, baseSha, headSha);
        }

        ProcessResult fallback = runGit("diff", "--name-only", headSha);
        if (!fallback.success() && ciMode) {
            throw new AssertionError("FDP-39 CI fallback diff failed: " + fallback.stderr());
        }
        return new DiffResult(fallback.success(), eventName, "working-tree-fallback", baseRef, "", headSha,
                fallback.stdout(), fallback.stderr());
    }

    private String baseRef() {
        String githubBase = System.getenv("GITHUB_BASE_REF");
        if (githubBase != null && !githubBase.isBlank()) {
            return githubBase.trim();
        }
        String configured = System.getProperty("fdp39.base-ref");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "master";
    }

    private void writeRuntimeImmutabilityArtifact(DiffResult diff, List<String> protectedRuntimeFiles) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("diff_computed", diff.diffComputed());
        root.put("event_name", diff.eventName());
        root.put("comparison_mode", diff.comparisonMode());
        root.put("base_ref", diff.baseRef());
        root.put("base_sha", diff.baseSha());
        root.put("head_sha", diff.headSha());
        root.put("changed_file_count", diff.changedFiles().size());
        root.put("protected_runtime_file_count", protectedRuntimeFiles.size());
        root.put("runtime_semantics_unchanged", protectedRuntimeFiles.isEmpty());
        if (!diff.stderr().isBlank()) {
            root.put("git_stderr", diff.stderr());
        }
        ArrayNode protectedFiles = root.putArray("protected_runtime_files");
        protectedRuntimeFiles.forEach(protectedFiles::add);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(OUTPUT_DIR.resolve("fdp39-runtime-immutability.json").toFile(), root);
    }

    private DiffResult diffBetween(
            String eventName,
            String comparisonMode,
            String baseRef,
            String baseRevision,
            String headRevision
    ) throws IOException, InterruptedException {
        boolean ciMode = Boolean.getBoolean("fdp39.ci-mode");
        String baseSha = verifiedCommit("base", baseRevision, ciMode);
        String headSha = verifiedCommit("head", headRevision, ciMode);
        ProcessResult diff = runGit("diff", "--name-only", baseSha, headSha);
        if (!diff.success() && ciMode) {
            throw new AssertionError("FDP-39 CI failed to compute changed files: " + diff.stderr());
        }
        return new DiffResult(diff.success(), eventName, comparisonMode, baseRef, baseSha, headSha,
                diff.stdout(), diff.stderr());
    }

    private String verifiedCommit(String label, String revision, boolean ciMode) throws IOException, InterruptedException {
        ProcessResult verify = runGit("rev-parse", "--verify", revision + "^{commit}");
        if (!verify.success() && ciMode) {
            throw new AssertionError("FDP-39 CI failed to verify " + label + " revision " + revision + ": "
                    + verify.stderr());
        }
        if (verify.success() && !verify.stdout().isEmpty()) {
            return verify.stdout().getFirst();
        }
        return revision;
    }

    private String propertyOrEnv(String property, String environment, String fallback) {
        String configured = System.getProperty(property);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String value = System.getenv(environment);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback;
    }

    private boolean isZeroSha(String sha) {
        return sha.chars().allMatch(character -> character == '0');
    }

    private ProcessResult runGit(String... args) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(command(args));
        builder.directory(Path.of("..").toAbsolutePath().normalize().toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(false, List.of(), "git command timed out: " + String.join(" ", args));
        }
        List<String> stdout;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = reader.lines().filter(line -> !line.isBlank()).toList();
        }
        String stderr;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            stderr = String.join("\n", reader.lines().toList());
        }
        return new ProcessResult(process.exitValue() == 0, stdout, stderr);
    }

    private List<String> command(String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=C:/Users/mpods/IdeaProjects/fraud-detection-platform");
        command.addAll(List.of(args));
        return command;
    }

    private record DiffResult(
            boolean diffComputed,
            String eventName,
            String comparisonMode,
            String baseRef,
            String baseSha,
            String headSha,
            List<String> changedFiles,
            String stderr
    ) {
    }

    private record ProcessResult(boolean success, List<String> stdout, String stderr) {
    }
}
