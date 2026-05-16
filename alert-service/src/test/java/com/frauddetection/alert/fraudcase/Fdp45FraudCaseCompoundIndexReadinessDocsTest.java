package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseCompoundIndexReadinessDocsTest {

    @Test
    void docsNameCompoundIndexReadinessWithoutClaimingEveryQueryIsOptimized() throws IOException {
        String docs = Files.readString(projectRoot().resolve("docs/fdp/fdp_45_work_queue_readiness.md")).toLowerCase();

        assertThat(docs)
                .contains("status + createdat + _id")
                .contains("assignedinvestigatorid + createdat + _id")
                .contains("priority + createdat + _id")
                .contains("risklevel + createdat + _id")
                .contains("linkedalertids + createdat + _id")
                .contains("status + updatedat + _id")
                .contains("assignedinvestigatorid + updatedat + _id")
                .contains("minimum allowed-sort index readiness")
                .contains("ships compound index readiness")
                .contains("compound index definitions in `fraudcasedocument`")
                .contains("not a claim that every filter/sort combination is production-optimized");
        assertThat(docs)
                .doesNotContain("every production workload is fully optimized");
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
