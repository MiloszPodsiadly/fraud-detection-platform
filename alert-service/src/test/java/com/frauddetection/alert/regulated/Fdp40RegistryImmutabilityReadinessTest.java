package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.bool;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static org.assertj.core.api.Assertions.assertThat;

class Fdp40RegistryImmutabilityReadinessTest {

    @Test
    void registryImmutabilityRequiresExternalProviderEvidence() throws Exception {
        String docs = Files.readString(Path.of("../docs/release/FDP-40-registry-immutability-readiness.md"));
        Map<String, Object> readiness = readJson(Path.of("../docs/release/FDP-40-registry-immutability-readiness.json"));

        assertThat(docs)
                .contains("does not verify registry immutability through registry provider APIs")
                .contains("does not enforce registry immutability");
        assertThat(bool(readiness, "registry_immutability_required")).isTrue();
        assertThat(bool(readiness, "verified_by_fdp40")).isFalse();
        assertThat(bool(readiness, "mutable_tag_overwrite_prohibited")).isTrue();
        assertThat(bool(readiness, "promotion_by_digest_required")).isTrue();
        assertThat(bool(readiness, "fixture_repository_promotable")).isFalse();
        assertThat(bool(readiness, "release_repository_required")).isTrue();
        assertThat(bool(readiness, "retention_policy_required")).isTrue();
        assertThat(bool(readiness, "external_platform_control_required")).isTrue();
    }
}
