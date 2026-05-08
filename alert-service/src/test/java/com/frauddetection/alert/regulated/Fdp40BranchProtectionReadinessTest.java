package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.bool;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static org.assertj.core.api.Assertions.assertThat;

class Fdp40BranchProtectionReadinessTest {

    @Test
    void branchProtectionIsRequiredButNotVerifiedByFdp40() throws Exception {
        String docs = Files.readString(Path.of("../docs/release/fdp-40-branch-protection-readiness.md"));
        Map<String, Object> readiness = readJson(Path.of("../docs/release/fdp-40-branch-protection-readiness.json"));

        assertThat(docs)
                .contains("does not verify GitHub branch protection through GitHub APIs")
                .contains("Production enablement is NO-GO");
        assertThat(bool(readiness, "branch_protection_required")).isTrue();
        assertThat(bool(readiness, "verified_by_fdp40")).isFalse();
        assertThat(bool(readiness, "required_checks_must_match_matrix")).isTrue();
        assertThat(bool(readiness, "dismiss_stale_reviews_required")).isTrue();
        assertThat(bool(readiness, "require_code_owner_reviews")).isTrue();
        assertThat(bool(readiness, "require_status_checks_before_merge")).isTrue();
        assertThat(bool(readiness, "admin_bypass_allowed")).isFalse();
        assertThat(readiness.get("required_checks_matrix_ref")).isEqualTo("docs/release/fdp-40-required-checks-matrix.json");
    }
}
