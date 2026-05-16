package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static org.assertj.core.api.Assertions.assertThat;

class Fdp40RequiredChecksMatrixTest {

    private static final Set<String> REQUIRED = Set.of(
            "backend",
            "docker",
            "regulated-mutation-regression",
            "fdp35-production-readiness",
            "fdp36-real-chaos",
            "fdp37-production-image-chaos",
            "fdp38-live-runtime-checkpoint-chaos",
            "fdp39-release-governance",
            "fdp40-release-controls"
    );

    @Test
    void requiredChecksMatrixListsAllBlockingReleaseGates() throws Exception {
        String markdown = Files.readString(Path.of("../docs/release/fdp_40_required_checks_matrix.md"));
        assertThat(markdown)
                .contains("branch protection")
                .contains("does not verify GitHub branch protection through GitHub APIs")
                .contains("External branch protection control is required");
        Map<String, Object> matrix = readJson(Path.of("../docs/release/fdp_40_required_checks_matrix.json"));
        List<?> rawChecks = (List<?>) matrix.get("checks");
        List<Map<String, Object>> checks = rawChecks.stream()
                .map(check -> (Map<String, Object>) check)
                .toList();
        Set<String> names = checks.stream()
                .map(check -> check.get("check_name").toString())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrderElementsOf(REQUIRED);
        assertThat(matrix.get("required_checks_defined")).isEqualTo(true);
        assertThat(matrix.get("required_checks_platform_enforcement_verified_by_fdp40")).isEqualTo(false);
        assertThat(matrix.get("branch_protection_required")).isEqualTo(true);
        assertThat(matrix.get("external_branch_protection_control_required")).isEqualTo(true);
        for (Map<String, Object> check : checks) {
            assertThat(check.get("required")).isEqualTo(true);
            assertThat(check.get("blocking")).isEqualTo(true);
            assertThat(check.get("failure_policy")).isEqualTo("NO_GO");
            assertThat(check.get("owner")).as("owner required").isNotNull();
            assertThat(check.get("artifact_name")).as("artifact_name required").isNotNull();
        }
    }
}
