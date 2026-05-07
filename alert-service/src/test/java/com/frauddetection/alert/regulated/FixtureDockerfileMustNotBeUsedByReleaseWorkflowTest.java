package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureDockerfileMustNotBeUsedByReleaseWorkflowTest {

    private static final String FIXTURE_DOCKERFILE = "Dockerfile.alert-service-fdp38-fixture";

    @Test
    void fixtureDockerfileIsAbsentFromReleaseComposeScriptsAndReadme() throws Exception {
        assertFilesDoNotContain(Path.of("../deployment"), List.of(
                "Dockerfile.alert-service-fdp38-fixture"
        ));
        assertFilesDoNotContain(Path.of("../scripts"), List.of());
        assertThat(Files.readString(Path.of("../README.md"))).doesNotContain(FIXTURE_DOCKERFILE);

        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        int fdp38Start = ci.indexOf("fdp38-live-runtime-checkpoint-chaos:");
        int fdp39Start = ci.indexOf("fdp39-release-governance:");
        int dockerStart = ci.indexOf("\n  docker:", fdp38Start);
        String fdp38Job = fdp38Start >= 0 && fdp39Start > fdp38Start
                ? ci.substring(fdp38Start, fdp39Start)
                : "";
        String fdp39Job = fdp39Start >= 0
                ? ci.substring(fdp39Start, dockerStart)
                : "";
        assertFixtureDockerfileReferencesAreOnlyInAllowedCiRegion(ci, fdp38Start, dockerStart);
        assertThat(fdp38Job).contains("docker build -f deployment/" + FIXTURE_DOCKERFILE);
        assertThat(ci).contains("deployment/Dockerfile.backend");

        writeUsageArtifact(ci, fdp38Job, fdp39Job);
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

    private void assertFixtureDockerfileReferencesAreOnlyInAllowedCiRegion(
            String ci,
            int allowedStart,
            int allowedEnd
    ) {
        assertThat(allowedStart).isGreaterThanOrEqualTo(0);
        assertThat(allowedEnd).isGreaterThan(allowedStart);
        int index = ci.indexOf(FIXTURE_DOCKERFILE);
        while (index >= 0) {
            assertThat(allowedStart <= index && index < allowedEnd)
                    .as("FDP-38 fixture Dockerfile reference must only appear in FDP-38/FDP-39 CI jobs")
                    .isTrue();
            index = ci.indexOf(FIXTURE_DOCKERFILE, index + FIXTURE_DOCKERFILE.length());
        }
    }

    private void writeUsageArtifact(String ci, String fdp38Job, String fdp39Job) throws IOException {
        Path outputDir = Path.of("target", "fdp39-governance");
        Files.createDirectories(outputDir);
        int allowedCount = occurrences(fdp38Job, FIXTURE_DOCKERFILE) + occurrences(fdp39Job, FIXTURE_DOCKERFILE);
        int totalCount = occurrences(ci, FIXTURE_DOCKERFILE);
        int forbiddenCount = totalCount - allowedCount;
        Files.writeString(outputDir.resolve("fdp39-fixture-dockerfile-usage.md"), """
                # FDP-39 Fixture Dockerfile Usage

                - allowed_occurrence_count: %d
                - forbidden_occurrence_count: %d
                - allowed_locations: FDP-38 live checkpoint CI, FDP-39 release governance guard
                - release_workflow_safe: %s
                """.formatted(allowedCount, forbiddenCount, forbiddenCount == 0));
        assertThat(forbiddenCount).isZero();
    }

    private int occurrences(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }
}
