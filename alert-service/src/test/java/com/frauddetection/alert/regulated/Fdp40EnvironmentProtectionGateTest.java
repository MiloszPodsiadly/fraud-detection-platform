package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.bool;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fdp40EnvironmentProtectionGateTest {

    @Test
    void environmentProtectionGatesRequireApprovalDualControlAndImmutableDigest() throws Exception {
        assertThat(Files.readString(Path.of("../docs/release/FDP-40-environment-protection-gates.md")))
                .contains("Production deploy requires dual control")
                .contains("FDP-29 enablement requires a separate config PR");
        Map<String, Object> gates = readJson(Path.of("../docs/release/FDP-40-environment-protection-gates.json"));

        assertGatesValid(gates);
    }

    @Test
    void invalidEnvironmentGateCasesFailClosed() throws Exception {
        Map<String, Object> valid = readJson(Path.of("../docs/release/FDP-40-environment-protection-gates.json"));

        assertInvalid(valid, gates -> gates.put("staging_deploy_requires_approval", false));
        assertInvalid(valid, gates -> gates.put("production_deploy_requires_approval", false));
        assertInvalid(valid, gates -> gates.put("production_deploy_requires_dual_control", false));
        assertInvalid(valid, gates -> gates.put("approver_cannot_equal_release_author", false));
        assertInvalid(valid, gates -> gates.put("deployment_references_immutable_digest", false));
        assertInvalid(valid, gates -> gates.put("deployment_uses_fixture_image", true));
        assertInvalid(valid, gates -> gates.put("rollback_owner_required", false));
        assertInvalid(valid, gates -> gates.put("fdp29_enablement_requires_separate_config_pr", false));
    }

    private void assertGatesValid(Map<String, Object> gates) {
        assertThat(bool(gates, "staging_deploy_requires_approval")).isTrue();
        assertThat(bool(gates, "production_deploy_requires_approval")).isTrue();
        assertThat(bool(gates, "production_deploy_requires_dual_control")).isTrue();
        assertThat(bool(gates, "approver_cannot_equal_release_author")).isTrue();
        assertThat(bool(gates, "rollback_owner_required")).isTrue();
        assertThat(bool(gates, "security_owner_required")).isTrue();
        assertThat(bool(gates, "fraud_ops_owner_required")).isTrue();
        assertThat(bool(gates, "platform_owner_required")).isTrue();
        assertThat(bool(gates, "deployment_references_immutable_digest")).isTrue();
        assertThat(bool(gates, "deployment_references_release_manifest")).isTrue();
        assertThat(bool(gates, "deployment_references_rollback_plan")).isTrue();
        assertThat(bool(gates, "deployment_uses_fixture_image")).isFalse();
        assertThat(bool(gates, "fdp29_enablement_requires_separate_config_pr")).isTrue();
    }

    private void assertInvalid(Map<String, Object> valid, java.util.function.Consumer<Map<String, Object>> mutation) {
        Map<String, Object> candidate = new LinkedHashMap<>(valid);
        mutation.accept(candidate);
        assertThatThrownBy(() -> assertGatesValid(candidate))
                .isInstanceOf(AssertionError.class);
    }
}
