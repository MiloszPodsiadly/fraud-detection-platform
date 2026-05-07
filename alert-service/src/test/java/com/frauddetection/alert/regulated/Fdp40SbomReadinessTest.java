package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.bool;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.string;
import static org.assertj.core.api.Assertions.assertThat;

class Fdp40SbomReadinessTest {

    @Test
    void sbomReadinessRequiresExternalGenerationRetentionAndSecurityReview() throws Exception {
        String docs = Files.readString(Path.of("../docs/release/FDP-40-sbom-readiness-policy.md"));
        Map<String, Object> sbom = readJson(Path.of("../docs/release/FDP-40-sbom-readiness-template.json"));

        assertThat(docs)
                .contains("does not generate a production SBOM")
                .contains("not runtime correctness proof")
                .contains("external finality")
                .contains("bank certification");
        assertThat(bool(sbom, "sbom_required_before_production_enablement")).isTrue();
        assertThat(bool(sbom, "sbom_generated_by_fdp40")).isFalse();
        List<String> formats = ((List<?>) sbom.get("sbom_format_allowed")).stream()
                .map(Object::toString)
                .toList();
        assertThat(formats)
                .contains("SPDX")
                .contains("CycloneDX");
        assertThat(string(sbom, "image_digest")).startsWith("sha256:");
        assertThat(string(sbom, "commit_sha")).isNotBlank();
        assertThat(string(sbom, "build_workflow")).isNotBlank();
        assertThat(string(sbom, "generation_tool")).isNotBlank();
        assertThat(bool(sbom, "retention_policy_required")).isTrue();
        assertThat(bool(sbom, "vulnerability_review_required")).isTrue();
        assertThat(bool(sbom, "security_owner_required")).isTrue();
    }
}
