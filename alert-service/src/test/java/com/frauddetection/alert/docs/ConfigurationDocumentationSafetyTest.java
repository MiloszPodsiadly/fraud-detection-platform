package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationDocumentationSafetyTest {

    @Test
    void configurationGuideSeparatesLocalFixtureAndProductionLikeUse() throws Exception {
        String guide = Files.readString(DocumentationTestSupport.docsRoot().resolve("configuration/configuration-guide.md"));

        assertThat(guide)
                .contains("Mode/profile")
                .contains("Allowed in local/dev")
                .contains("Allowed in test")
                .contains("Allowed in production-like proof")
                .contains("Allowed in release image")
                .contains("Transaction mode expectation")
                .contains("FDP-29 enablement rule")
                .contains("Fixture/checkpoint barrier rule")
                .contains("Required guardrails")
                .contains("Forbidden claims")
                .contains("local/dev")
                .contains("test")
                .contains("production-like")
                .contains("FDP-38 fixture image/profile")
                .contains("FDP-37/FDP-39/FDP-40 release image")
                .contains("enablement config PR")
                .contains("`OFF` is compatibility/demo only")
                .contains("`REQUIRED` is expected for bank/prod-style regulated mutation safety")
                .contains("FDP-29 evidence-gated finalize requires a separate config PR")
                .contains("Checkpoint renewal is ownership preservation only")
                .contains("Mutable tag deployment is NO-GO")
                .contains("Missing image digest is NO-GO")
                .contains("READY_FOR_ENABLEMENT_REVIEW does not mean PRODUCTION_ENABLED")
                .contains("must not be used as release profiles or release images")
                .contains("must never be included in a release image claim");
    }
}
