package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.bool;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static org.assertj.core.api.Assertions.assertThat;

class Fdp40DeploymentEnvironmentReadinessTest {

    @Test
    void deploymentEnvironmentProtectionIsRequiredButExternal() throws Exception {
        String docs = Files.readString(Path.of("../docs/release/FDP-40-deployment-environment-readiness.md"));
        Map<String, Object> readiness = readJson(Path.of("../docs/release/FDP-40-deployment-environment-readiness.json"));

        assertThat(docs)
                .contains("does not verify those protections through GitHub or deployment-platform APIs")
                .contains("Enablement is NO-GO");
        assertThat(bool(readiness, "staging_environment_protection_required")).isTrue();
        assertThat(bool(readiness, "production_environment_protection_required")).isTrue();
        assertThat(bool(readiness, "required_reviewers_required")).isTrue();
        assertThat(bool(readiness, "single_release_owner_model")).isTrue();
        assertThat(bool(readiness, "release_owner_required")).isTrue();
        assertThat(bool(readiness, "release_owner_must_be_named")).isTrue();
        assertThat(bool(readiness, "dual_control_required")).isFalse();
        assertThat(bool(readiness, "secrets_access_limited_to_environment")).isTrue();
        assertThat(bool(readiness, "deployment_must_reference_digest")).isTrue();
        assertThat(bool(readiness, "verified_by_fdp40")).isFalse();
        assertThat(bool(readiness, "external_platform_control_required")).isTrue();
    }
}
