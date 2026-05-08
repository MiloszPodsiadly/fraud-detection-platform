package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.bool;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static org.assertj.core.api.Assertions.assertThat;

class Fdp40ExternalPlatformControlsMatrixTest {

    private static final Set<String> REQUIRED_CONTROLS = Set.of(
            "cosign/Sigstore signing verification",
            "Rekor/transparency log verification",
            "registry immutability / tag overwrite protection",
            "registry promotion policy by digest",
            "GitHub branch protection required checks",
            "GitHub environment required reviewers",
            "deployment environment protection",
            "artifact retention policy",
            "release approval audit trail",
            "SBOM generation and retention",
            "provenance attestation retention",
            "rollback approval trail"
    );

    @Test
    void externalPlatformControlsAreExplicitReadinessGaps() throws Exception {
        String docs = Files.readString(Path.of("../docs/release/fdp-40-external-platform-controls-matrix.md"));
        assertThat(docs)
                .contains("readiness is not full platform enforcement")
                .contains("Production enablement is NO-GO");

        Map<String, Object> matrix = readJson(Path.of("../docs/release/fdp-40-external-platform-controls-matrix.json"));
        List<Map<String, Object>> controls = ((List<?>) matrix.get("controls")).stream()
                .map(item -> (Map<String, Object>) item)
                .toList();
        Set<String> names = controls.stream()
                .map(control -> control.get("control_name").toString())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrderElementsOf(REQUIRED_CONTROLS);
        for (Map<String, Object> control : controls) {
            assertThat(bool(control, "required_before_production_enablement")).isTrue();
            assertThat(bool(control, "enforced_by_fdp40")).isFalse();
            assertThat(control.get("owner")).isNotNull();
            assertThat(control.get("evidence_required")).isNotNull();
            assertThat(control.get("failure_policy")).isEqualTo("NO_GO_FOR_PRODUCTION_ENABLEMENT");
            assertThat(control.get("future_enforcement_path")).isNotNull();
        }
    }
}
