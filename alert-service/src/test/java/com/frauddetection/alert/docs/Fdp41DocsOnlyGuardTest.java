package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp41DocsOnlyGuardTest {

    private static final List<String> FORBIDDEN_RUNTIME_PREFIXES = List.of(
            "alert-service/src/main/java/",
            "audit-trust-authority/src/main/java/",
            "common-events/src/main/java/",
            "feature-enricher-service/src/main/java/",
            "fraud-scoring-service/src/main/java/",
            "transaction-ingest-service/src/main/java/",
            "transaction-simulator-service/src/main/java/",
            "alert-service/src/main/resources/application.yml",
            "alert-service/src/main/resources/application-bank.yml"
    );

    private static final List<String> FORBIDDEN_RUNTIME_FRAGMENTS = List.of(
            "/controller/",
            "/api/",
            "/regulated/mutation/",
            "/regulated/MongoRegulatedMutationCoordinator",
            "/regulated/LegacyRegulatedMutationExecutor",
            "/regulated/EvidenceGatedFinalizeExecutor",
            "/outbox/",
            "/messaging/"
    );

    @Test
    void fdp41DoesNotChangeRuntimeBehaviorFiles() throws Exception {
        List<String> changedFiles = changedFilesAgainstBase();
        List<String> forbidden = changedFiles.stream()
                .map(path -> path.replace('\\', '/'))
                .filter(this::isForbiddenRuntimeFile)
                .toList();

        assertThat(forbidden)
                .as("FDP-41 is docs-only and must not change runtime behavior files")
                .isEmpty();
    }

    private boolean isForbiddenRuntimeFile(String path) {
        if (FORBIDDEN_RUNTIME_PREFIXES.stream().anyMatch(path::startsWith)) {
            return true;
        }
        return path.endsWith("application.yml")
                || path.endsWith("application-bank.yml")
                || (path.contains("/src/main/") && FORBIDDEN_RUNTIME_FRAGMENTS.stream().anyMatch(path::contains));
    }

    private List<String> changedFilesAgainstBase() throws Exception {
        Path repositoryRoot = DocumentationTestSupport.repositoryRoot();
        String baseRef = resolveBaseRef(repositoryRoot);
        GitResult mergeBase = git(repositoryRoot, "merge-base", "HEAD", baseRef);
        assertThat(mergeBase.success())
                .as("FDP-41 docs-only guard must compute merge-base against " + baseRef + ": " + mergeBase.stderr())
                .isTrue();
        assertThat(mergeBase.stdout()).isNotEmpty();

        GitResult diff = git(repositoryRoot, "diff", "--no-renames", "--name-only", mergeBase.stdout().getFirst(), "HEAD");
        assertThat(diff.success())
                .as("FDP-41 docs-only guard must compute changed files: " + diff.stderr())
                .isTrue();
        return diff.stdout();
    }

    private String resolveBaseRef(Path repositoryRoot) throws Exception {
        GitResult originMaster = git(repositoryRoot, "rev-parse", "--verify", "origin/master");
        if (originMaster.success()) {
            return "origin/master";
        }
        GitResult master = git(repositoryRoot, "rev-parse", "--verify", "master");
        assertThat(master.success())
                .as("FDP-41 docs-only guard requires origin/master or local master: " + master.stderr())
                .isTrue();
        return "master";
    }

    private GitResult git(Path repositoryRoot, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=" + repositoryRoot.toString().replace('\\', '/'));
        command.add("-C");
        command.add(repositoryRoot.toString());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).start();
        CompletableFuture<List<String>> stdoutFuture = CompletableFuture.supplyAsync(
                () -> readNonBlankLines(process.getInputStream())
        );
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                () -> readAll(process.getErrorStream())
        );
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            return new GitResult(false, List.of(), "git command timed out: " + String.join(" ", args));
        }
        List<String> stdout = await(stdoutFuture);
        String stderr = await(stderrFuture);
        return new GitResult(process.exitValue() == 0, stdout, stderr);
    }

    private List<String> readNonBlankLines(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String readAll(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return String.join("\n", reader.lines().toList());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private <T> T await(CompletableFuture<T> future) throws IOException, InterruptedException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof UncheckedIOException uncheckedIOException) {
                throw uncheckedIOException.getCause();
            }
            throw new IOException("Failed to read git process output", cause);
        } catch (TimeoutException exception) {
            throw new IOException("Timed out reading git process output", exception);
        }
    }

    private record GitResult(boolean success, List<String> stdout, String stderr) {
    }
}
