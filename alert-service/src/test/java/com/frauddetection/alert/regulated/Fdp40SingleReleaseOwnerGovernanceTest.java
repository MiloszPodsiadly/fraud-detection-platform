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

class Fdp40SingleReleaseOwnerGovernanceTest {

    @Test
    void singleReleaseOwnerGovernanceRequiresNamedAccountableOwnerWithoutDualControlClaim() throws Exception {
        String docs = Files.readString(Path.of("../docs/release/fdp_40_single_release_owner_governance.md"));
        Map<String, Object> governance = readJson(
                Path.of("../docs/release/fdp_40_single_release_owner_governance.json")
        );

        assertThat(docs)
                .contains("single release owner model")
                .contains("does not require dual-control")
                .contains("release image digest")
                .contains("operator drill evidence")
                .contains("separate config PR");
        assertGovernanceValid(governance);
    }

    @Test
    void invalidSingleReleaseOwnerGovernanceFailsClosed() throws Exception {
        Map<String, Object> valid = readJson(Path.of("../docs/release/fdp_40_single_release_owner_governance.json"));

        assertInvalid(valid, governance -> governance.put("release_owner_required", false));
        assertInvalid(valid, governance -> governance.put("release_owner_must_be_named", false));
        assertInvalid(valid, governance -> governance.put("release_owner_must_confirm_digest", false));
        assertInvalid(valid, governance -> governance.put("release_owner_must_confirm_rollback_plan", false));
        assertInvalid(valid, governance -> governance.put("release_owner_must_confirm_operator_drill", false));
        assertInvalid(valid, governance -> governance.put("separate_config_pr_required", false));
        assertInvalid(valid, governance -> governance.put("production_enabled", true));
        assertInvalid(valid, governance -> governance.put("dual_control_required", true));
    }

    private void assertGovernanceValid(Map<String, Object> governance) {
        assertThat(bool(governance, "single_release_owner_model")).isTrue();
        assertThat(bool(governance, "release_owner_required")).isTrue();
        assertThat(bool(governance, "release_owner_must_be_named")).isTrue();
        assertThat(bool(governance, "release_owner_accountability_required")).isTrue();
        assertThat(bool(governance, "release_owner_must_confirm_digest")).isTrue();
        assertThat(bool(governance, "release_owner_must_confirm_required_checks")).isTrue();
        assertThat(bool(governance, "release_owner_must_confirm_rollback_plan")).isTrue();
        assertThat(bool(governance, "release_owner_must_confirm_operator_drill")).isTrue();
        assertThat(bool(governance, "release_owner_must_confirm_config_pr")).isTrue();
        assertThat(bool(governance, "separate_config_pr_required")).isTrue();
        assertThat(bool(governance, "production_enabled")).isFalse();
        assertThat(bool(governance, "dual_control_required")).isFalse();
    }

    private void assertInvalid(Map<String, Object> valid, java.util.function.Consumer<Map<String, Object>> mutation) {
        Map<String, Object> candidate = new LinkedHashMap<>(valid);
        mutation.accept(candidate);
        assertThatThrownBy(() -> assertGovernanceValid(candidate))
                .isInstanceOf(AssertionError.class);
    }
}
