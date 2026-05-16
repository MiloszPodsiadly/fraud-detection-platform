package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp40CosignVerificationReadinessTest {

    @Test
    void cosignReadinessScriptDefaultsToNonEnforcementMode() throws Exception {
        String docs = Files.readString(Path.of("../docs/release/fdp_40_cosign_verification_readiness.md"));
        String script = Files.readString(Path.of("../scripts/fdp40-verify-cosign-signature.sh"));

        assertThat(docs)
                .contains("default mode is readiness-only")
                .contains("does not perform real cosign")
                .contains("verification_performed: false");
        assertThat(script)
                .startsWith("#!/usr/bin/env bash")
                .contains("set -euo pipefail")
                .contains("FDP40_COSIGN_ENFORCEMENT")
                .contains("\"verification_performed\": False")
                .contains("cosign verify")
                .contains("--certificate-identity")
                .contains("--certificate-oidc-issuer");
    }

    @Test
    void enforcementModeFailsWithoutDigestIdentityAndIssuer() throws Exception {
        String script = Files.readString(Path.of("../scripts/fdp40-verify-cosign-signature.sh"));

        assertThat(script)
                .contains("release_image_digest_missing")
                .contains("cert_identity_missing")
                .contains("cert_issuer_missing")
                .contains("exit 1");
    }
}
