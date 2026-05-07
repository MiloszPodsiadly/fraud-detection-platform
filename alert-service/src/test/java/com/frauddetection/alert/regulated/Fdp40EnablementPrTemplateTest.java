package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp40EnablementPrTemplateTest {

    @Test
    void enablementPrTemplateRequiresSingleReleaseOwnerDigestConfigPrAndRollbackEvidence() throws Exception {
        Path template = Path.of("../.github/PULL_REQUEST_TEMPLATE/fdp-enablement-config-change.md");
        assertThat(Files.exists(template)).isTrue();
        String content = Files.readString(template);

        assertThat(content)
                .doesNotContain("enablemen" + "ment")
                .doesNotContain("Dual-Control " + "Approvers")
                .doesNotContain("Approver " + "1")
                .doesNotContain("Approver " + "2")
                .doesNotContain("dual_control")
                .doesNotContain("dual approval")
                .contains("Release Image Digest")
                .contains("Release Manifest Link")
                .contains("FDP-39 Provenance Artifact Link")
                .contains("FDP-40 Signing / Attestation Readiness Link")
                .contains("Release owner is named")
                .contains("single release owner model")
                .contains("immutable release image digest")
                .contains("separate config PR")
                .contains("required checks are green")
                .contains("Rollback Plan")
                .contains("rollback plan")
                .contains("operator drill")
                .contains("Security review evidence")
                .contains("Fraud Ops review evidence")
                .contains("Platform review evidence")
                .contains("Admin-only access is verified")
                .contains("Sensitive read audit is enabled")
                .contains("Rate limit requirement is linked")
                .contains("Raw leaseOwner, idempotencyKey, requestHash, and lastError exposure is forbidden")
                .contains("does not claim production certification")
                .contains("does not claim bank certification")
                .contains("READY_FOR_ENABLEMENT_REVIEW is not PRODUCTION_ENABLED");
    }
}
