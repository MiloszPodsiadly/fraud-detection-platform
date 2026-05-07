package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.assertAttestationValid;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readYamlKeyValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fdp40SignedProvenanceReadinessTest {

    private static final Path MANIFEST = Path.of("../docs/release/FDP-40-release-manifest-template.yaml");
    private static final Path ATTESTATION = Path.of("../docs/release/FDP-40-attestation-readiness-template.json");

    @Test
    void signedProvenanceReadinessRequiresReleaseImageAttestationFields() throws Exception {
        assertThat(Files.exists(Path.of("../scripts/fdp40-validate-attestation-readiness.sh"))).isTrue();
        assertThat(Files.readString(Path.of("../docs/release/FDP-40-signed-provenance-policy.md")))
                .contains("Signed artifact proof is release provenance evidence only")
                .contains("does not claim external finality");

        assertAttestationValid(readJson(ATTESTATION), readYamlKeyValues(MANIFEST));
    }

    @Test
    void invalidAttestationCasesFailClosed() throws Exception {
        Map<String, String> manifest = readYamlKeyValues(MANIFEST);
        Map<String, Object> valid = readJson(ATTESTATION);

        assertInvalid(valid, manifest, attestation -> attestation.remove("signature_subject"));
        assertInvalid(valid, manifest, attestation -> attestation.remove("builder_identity"));
        assertInvalid(valid, manifest, attestation -> attestation.remove("source_repository"));
        assertInvalid(valid, manifest, attestation -> attestation.remove("image_digest"));
        assertInvalid(valid, manifest, attestation -> attestation.put("image_digest", "sha256:999"));
        assertInvalid(valid, manifest, attestation -> attestation.put("builder_identity", "unexpected-builder"));
        assertInvalid(valid, manifest, attestation -> attestation.put("image_digest", manifest.get("fixture_image_digest")));
        assertInvalid(valid, manifest, attestation -> attestation.put("image_digest", "alert-service:latest"));
    }

    private void assertInvalid(
            Map<String, Object> valid,
            Map<String, String> manifest,
            java.util.function.Consumer<Map<String, Object>> mutation
    ) {
        Map<String, Object> candidate = new LinkedHashMap<>(valid);
        mutation.accept(candidate);
        assertThatThrownBy(() -> assertAttestationValid(candidate, manifest))
                .isInstanceOf(AssertionError.class);
    }
}
