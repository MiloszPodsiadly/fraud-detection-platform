package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp40ScriptExecutionReadinessTest {

    private static final List<String> SCRIPTS = List.of(
            "scripts/fdp40-validate-release-manifest.sh",
            "scripts/fdp40-validate-attestation-readiness.sh",
            "scripts/fdp40-verify-release-evidence.sh",
            "scripts/fdp40-verify-cosign-signature.sh"
    );

    @Test
    void fdp40ScriptsAreShellSafeAndReferencedByCi() throws Exception {
        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        String job = ci.substring(ci.indexOf("fdp40-release-controls:"));

        for (String scriptPath : SCRIPTS) {
            String script = Files.readString(Path.of("..", scriptPath));
            assertThat(script)
                    .as(scriptPath)
                    .startsWith("#!/usr/bin/env bash")
                    .contains("set -euo pipefail");
            assertThat(job).contains("bash " + scriptPath);
        }
        assertThat(job)
                .contains("fetch-depth: 0")
                .contains("-Dfdp40.ci-mode=true")
                .contains("-Dfdp40.base-ref=${FDP40_BASE_REF}");
    }
}
