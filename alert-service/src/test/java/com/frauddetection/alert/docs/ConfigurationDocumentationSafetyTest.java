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
                .contains("local/dev/docker-local")
                .contains("fixture/test-only")
                .contains("production-like")
                .contains("transaction-mode=OFF` is compatibility/demo behavior")
                .contains("FDP-29 evidence-gated finalize requires a separate config PR")
                .contains("Mutable tag deployment is NO-GO")
                .contains("Missing image digest is NO-GO")
                .contains("must not be used as release profiles or release images");
    }
}
