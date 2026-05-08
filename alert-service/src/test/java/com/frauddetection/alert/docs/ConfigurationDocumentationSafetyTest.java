package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationDocumentationSafetyTest {

    @Test
    void configurationGuideSeparatesLocalFixtureAndProductionLikeUse() throws Exception {
        String guide = Files.readString(Path.of("../docs/configuration/configuration-guide.md"));

        assertThat(guide)
                .contains("local/dev")
                .contains("test")
                .contains("production-like")
                .contains("FDP-38 fixture image/profile")
                .contains("release image")
                .contains("Fixture profiles")
                .contains("Checkpoint barriers")
                .contains("Production enablement")
                .contains("transaction-mode=OFF` is compatibility/demo behavior")
                .contains("`REQUIRED` is expected for bank/prod-style regulated mutation safety")
                .contains("FDP-29 evidence-gated finalize requires a separate config PR")
                .contains("Checkpoint renewal is ownership preservation only")
                .contains("Mutable tag deployment is NO-GO")
                .contains("Missing image digest is NO-GO")
                .contains("must not be used as release profiles or release images")
                .contains("must never be included in a release image claim");
    }
}
