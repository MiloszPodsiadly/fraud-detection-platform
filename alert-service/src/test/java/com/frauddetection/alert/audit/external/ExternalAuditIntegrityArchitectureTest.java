package com.frauddetection.alert.audit.external;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalAuditIntegrityArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src/main/java/com/frauddetection/alert/audit/external");

    @Test
    void integrityAndCoverageComponentsStayIndependentFromApiMutationCode() throws Exception {
        List<String> componentNames = List.of(
                "ExternalAnchorIntegrityVerifier.java",
                "ExternalAnchorCoverageCalculator.java",
                "ExternalAuditIntegrityResponseFactory.java",
                "ExternalSignaturePolicyEvaluator.java"
        );

        for (String componentName : componentNames) {
            String source = Files.readString(SOURCE_ROOT.resolve(componentName));
            assertThat(source)
                    .as(componentName)
                    .doesNotContain(".controller.")
                    .doesNotContain(".regulated.")
                    .doesNotContain("MutationCommand")
                    .doesNotContain("@RestController");
        }
    }
}
