package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureDockerfileMustNotBeUsedByReleaseWorkflowTest {

    private static final String FIXTURE_DOCKERFILE = "Dockerfile.alert-service-fdp38-fixture";
    private static final Path OUTPUT_DIR = Path.of("target", "fdp39-governance");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fixtureDockerfileIsAbsentFromReleaseComposeScriptsAndReadme() throws Exception {
        assertFilesDoNotContain(Path.of("../deployment"), List.of(
                "Dockerfile.alert-service-fdp38-fixture"
        ));
        assertFilesDoNotContain(Path.of("../scripts"), List.of("fdp39-generate-governance-artifacts.sh"));
        assertThat(Files.readString(Path.of("../README.md"))).doesNotContain(FIXTURE_DOCKERFILE);

        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        String fdp38Job = jobSection(ci, "fdp38-live-runtime-checkpoint-chaos");
        String fdp39Job = jobSection(ci, "fdp39-release-governance");
        assertThat(fdp38Job).contains("docker build -f deployment/" + FIXTURE_DOCKERFILE);
        assertThat(ci).contains("deployment/Dockerfile.backend");

        writeUsageArtifact(findOccurrences());
    }

    private void assertFilesDoNotContain(Path root, List<String> excludedFileNames) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> excludedFileNames.stream().noneMatch(name -> path.getFileName().toString().equals(name)))
                    .toList();
            for (Path file : files) {
                assertThat(Files.readString(file))
                        .as("Release path must not reference FDP-38 fixture Dockerfile: " + file)
                        .doesNotContain(FIXTURE_DOCKERFILE);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to scan release workflow files", exception);
        }
    }

    private List<Occurrence> findOccurrences() throws IOException {
        assertThat(Files.exists(Path.of("../docs/release/fdp_39_fixture_dockerfile_allowlist.json")))
                .as("FDP-39 fixture Dockerfile structural allowlist must exist")
                .isTrue();
        List<Path> roots = List.of(
                Path.of("../.github"),
                Path.of("../deployment"),
                Path.of("../scripts"),
                Path.of("../docs"),
                Path.of("src/test/java")
        );
        List<Occurrence> occurrences = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String normalized = normalize(file);
                    if (normalized.contains("/target/")) {
                        continue;
                    }
                    String content = Files.readString(file);
                    int index = content.indexOf(FIXTURE_DOCKERFILE);
                    while (index >= 0) {
                        String jobName = normalized.equals(".github/workflows/ci.yml")
                                ? jobNameAt(content, index)
                                : "";
                        boolean allowed = isAllowed(normalized, jobName);
                        occurrences.add(new Occurrence(normalized, jobName, allowed));
                        assertThat(allowed)
                                .as("Fixture Dockerfile reference is not structurally allowlisted: " + normalized + "#" + jobName)
                                .isTrue();
                        index = content.indexOf(FIXTURE_DOCKERFILE, index + FIXTURE_DOCKERFILE.length());
                    }
                }
            }
        }
        return occurrences;
    }

    private boolean isAllowed(String path, String jobName) {
        if (path.equals(".github/workflows/ci.yml")) {
            return jobName.equals("fdp38-live-runtime-checkpoint-chaos")
                    || jobName.equals("fdp39-release-governance");
        }
        return path.equals("scripts/fdp39-generate-governance-artifacts.sh")
                || path.equals("deployment/Dockerfile.alert-service-fdp38-fixture")
                || path.matches("docs/fdp/fdp_38_.*\\.md")
                || path.matches("docs/adr/fdp_39_.*\\.md")
                || path.matches("docs/release/fdp_39_.*\\.(md|json)")
                || path.startsWith("alert-service/src/test/");
    }

    private String jobSection(String ci, String jobName) {
        int start = ci.indexOf("\n  " + jobName + ":");
        if (start < 0) {
            return "";
        }
        int end = ci.indexOf("\n  ", start + jobName.length() + 4);
        while (end >= 0 && end + 3 < ci.length() && Character.isWhitespace(ci.charAt(end + 3))) {
            end = ci.indexOf("\n  ", end + 1);
        }
        return end < 0 ? ci.substring(start) : ci.substring(start, end);
    }

    private String jobNameAt(String ci, int index) {
        int search = index;
        while (search >= 0) {
            int lineStart = ci.lastIndexOf('\n', search - 1);
            lineStart = lineStart < 0 ? 0 : lineStart + 1;
            int lineEnd = ci.indexOf('\n', lineStart);
            lineEnd = lineEnd < 0 ? ci.length() : lineEnd;
            String line = ci.substring(lineStart, lineEnd);
            if (line.startsWith("  ") && !line.startsWith("    ") && line.trim().endsWith(":")) {
                return line.trim().replace(":", "");
            }
            search = lineStart - 1;
        }
        return "";
    }

    private void writeUsageArtifact(List<Occurrence> occurrences) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        int totalCount = occurrences.size();
        int allowedCount = (int) occurrences.stream().filter(Occurrence::allowed).count();
        int forbiddenCount = totalCount - allowedCount;
        Files.writeString(OUTPUT_DIR.resolve("fdp39-fixture-dockerfile-usage.md"), """
                # FDP-39 Fixture Dockerfile Usage

                - allowed_occurrence_count: %d
                - forbidden_occurrence_count: %d
                - allowed_locations: FDP-38 live checkpoint CI, FDP-39 release governance guard
                - release_workflow_safe: %s
                """.formatted(allowedCount, forbiddenCount, forbiddenCount == 0));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("total_occurrence_count", totalCount);
        root.put("allowed_occurrence_count", allowedCount);
        root.put("forbidden_occurrence_count", forbiddenCount);
        root.put("fixture_dockerfile_release_safe", forbiddenCount == 0);
        ArrayNode forbidden = root.putArray("forbidden_occurrences");
        occurrences.stream()
                .filter(occurrence -> !occurrence.allowed())
                .forEach(occurrence -> forbidden.add(occurrence.path() + "#" + occurrence.jobName()));
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(OUTPUT_DIR.resolve("fdp39-fixture-dockerfile-usage.json").toFile(), root);
        assertThat(forbiddenCount).isZero();
    }

    private String normalize(Path path) {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        return repoRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private record Occurrence(String path, String jobName, boolean allowed) {
    }
}
