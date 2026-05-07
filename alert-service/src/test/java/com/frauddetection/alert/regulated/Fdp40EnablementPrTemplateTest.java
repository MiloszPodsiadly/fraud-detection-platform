package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp40EnablementPrTemplateTest {

    @Test
    void enablementPrTemplateRequiresDigestConfigPrDualControlAndRollbackEvidence() throws Exception {
        Path template = Path.of("../.github/PULL_REQUEST_TEMPLATE/fdp-enablement-config-change.md");
        assertThat(Files.exists(template)).isTrue();
        String content = Files.readString(template);

        assertThat(content)
                .doesNotContain("enablemenment")
                .contains("Release Image Digest")
                .contains("Release Manifest Link")
                .contains("FDP-39 Provenance Artifact Link")
                .contains("FDP-40 Signed Provenance / Attestation Link")
                .contains("Rollback Plan")
                .contains("Dual-Control Approvers")
                .contains("does not claim production certification")
                .contains("does not claim bank certification")
                .contains("READY_FOR_ENABLEMENT_REVIEW is not PRODUCTION_ENABLED");
    }
}
