package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueDocsNoOverclaimTest {

    @Test
    void fdp45DocsShouldDescribeReadinessWithoutMutationOrFinalityOverclaims() throws IOException {
        String docs = Files.readString(projectRoot().resolve("docs/fdp-45-work-queue-readiness.md")).toLowerCase();

        assertThat(docs)
                .contains("existing fraud-case query path")
                .contains("does not add a second search subsystem")
                .contains("derived at read time only")
                .contains("not persisted")
                .contains("does not change lifecycle mutation semantics")
                .contains("does not change")
                .contains("global exactly-once")
                .contains("external finality")
                .contains("bank certification claims");
        assertThat(docs)
                .doesNotContain("guarantees global exactly-once")
                .doesNotContain("guarantees external finality")
                .doesNotContain("bank certified")
                .doesNotContain("distributed acid guarantee")
                .doesNotContain("worm guaranteed");
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
