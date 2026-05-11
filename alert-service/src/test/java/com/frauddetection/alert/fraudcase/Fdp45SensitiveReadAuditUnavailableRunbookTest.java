package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45SensitiveReadAuditUnavailableRunbookTest {

    @Test
    void runbookDocumentsFailClosedAuditUnavailableResponseWithoutRawIdentifiers() throws IOException {
        Path runbook = projectRoot().resolve("docs/runbooks/fdp-45-sensitive-read-audit-unavailable.md");
        String body = Files.readString(runbook).toLowerCase();

        assertThat(body)
                .contains("fail-closed")
                .contains("do not disable audit")
                .contains("does not guarantee exactly-once audit")
                .contains("no raw identifiers")
                .contains("do not add raw identifiers")
                .contains("raw query strings")
                .contains("503");
        assertThat(body)
                .doesNotContain("case-1")
                .doesNotContain("customer-1")
                .doesNotContain("alert-1")
                .doesNotContain("disable read-access audit to restore reads");
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("docs"))) {
            return root;
        }
        return Path.of("..");
    }
}
