package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.assertReleaseManifestValid;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readYamlKeyValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fdp40ReleaseManifestValidationTest {

    private static final Path MANIFEST = Path.of("../docs/release/FDP-40-release-manifest-template.yaml");

    @Test
    void releaseManifestTemplateIsDigestBoundAndTiedToFdp39Provenance() throws Exception {
        assertThat(Files.exists(Path.of("../scripts/fdp40-validate-release-manifest.sh"))).isTrue();
        Map<String, String> manifest = readYamlKeyValues(MANIFEST);

        assertReleaseManifestValid(manifest);
        assertThat(manifest.get("release_image_tag"))
                .as("mutable tag may exist only beside immutable digest")
                .isNotBlank();
        assertThat(manifest.get("fdp39_provenance_artifact_ref")).contains("fdp39");
    }

    @Test
    void invalidReleaseManifestCasesFailClosed() throws Exception {
        Map<String, String> valid = readYamlKeyValues(MANIFEST);

        assertInvalid(valid, manifest -> {
            manifest.remove("release_image_digest");
            manifest.put("release_image_tag", "latest");
        });
        assertInvalid(valid, manifest -> manifest.remove("release_image_digest"));
        assertInvalid(valid, manifest -> manifest.put("fdp39_release_image_digest", "sha256:999"));
        assertInvalid(valid, manifest -> manifest.put("release_image_digest", manifest.get("fixture_image_digest")));
        assertInvalid(valid, manifest -> manifest.put("production_enabled", "true"));
        assertInvalid(valid, manifest -> manifest.put("bank_enabled", "true"));
        assertInvalid(valid, manifest -> manifest.put("readiness_only", "false"));
        assertInvalid(valid, manifest -> manifest.put("signed_provenance_readiness", "false"));
        assertInvalid(valid, manifest -> manifest.put("signing_enforced_by_fdp40", "true"));
        assertInvalid(valid, manifest -> manifest.put("registry_immutability_verified_by_fdp40", "true"));
        assertInvalid(valid, manifest -> manifest.put("required_checks_platform_enforcement_verified_by_fdp40", "true"));
        assertInvalid(valid, manifest -> manifest.put("single_release_owner_model", "false"));
        assertInvalid(valid, manifest -> manifest.put("release_owner_required", "false"));
        assertInvalid(valid, manifest -> manifest.put("release_owner_must_be_named", "false"));
        assertInvalid(valid, manifest -> manifest.put("release_config_pr_required", "false"));
        assertInvalid(valid, manifest -> manifest.put("dual_control_required", "true"));
        assertInvalid(valid, manifest -> manifest.put("dockerfile_path", "deployment/Dockerfile.alert-service-fdp38-fixture"));
        assertInvalid(valid, manifest -> manifest.put("release_image_digest", "PLACEHOLDER"));
    }

    @Test
    void nestedYamlManifestFailsClosed() throws Exception {
        Path nested = java.nio.file.Files.createTempFile("fdp40-nested", ".yaml");
        java.nio.file.Files.writeString(nested, """
                release_manifest_version: "1"
                nested:
                  release_image_digest: "sha256:111"
                """);

        assertThatThrownBy(() -> readYamlKeyValues(nested))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nested YAML is not supported");
    }

    @Test
    void complexYamlManifestFailsClosed() throws Exception {
        Path complex = java.nio.file.Files.createTempFile("fdp40-complex", ".yaml");
        java.nio.file.Files.writeString(complex, """
                release_manifest_version: "1"
                release_image_digest: ["sha256:111"]
                """);

        assertThatThrownBy(() -> readYamlKeyValues(complex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Complex YAML objects/lists are not supported");
    }

    private void assertInvalid(Map<String, String> valid, java.util.function.Consumer<Map<String, String>> mutation) {
        Map<String, String> candidate = new LinkedHashMap<>(valid);
        mutation.accept(candidate);
        assertThatThrownBy(() -> assertReleaseManifestValid(candidate))
                .isInstanceOf(AssertionError.class);
    }
}
