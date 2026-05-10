package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp42FraudCaseDocumentationNoOverclaimTest {

    private static final List<String> FDP42_DOCS = List.of(
            "docs/product/fraud-case-management.md",
            "docs/api/fraud-case-api.md",
            "docs/architecture/fraud-case-management-architecture.md",
            "docs/fdp-42-merge-gate.md",
            "docs/index.md"
    );

    @Test
    void fdp42DocsShouldStateLocalLifecycleScopeAndUnsupportedClaims() throws IOException {
        String docs = readDocs().toLowerCase(java.util.Locale.ROOT);

        assertThat(docs).contains("local audited lifecycle");
        assertThat(docs).contains("not evidence-gated");
        assertThat(docs).contains("not lease");
        assertThat(docs).contains("not replay");
        assertThat(docs).contains("not external finality");
        assertThat(docs).contains("not a `regulatedmutationcoordinator`");
        assertThat(docs).contains("not a regulated mutation finality claim");
    }

    @Test
    void fdp42DocsShouldDocumentAuditAuthorityAndNonIdempotentPosts() throws IOException {
        String docs = readDocs().toLowerCase(java.util.Locale.ROOT);

        assertThat(docs).contains("fraud-case:audit:read");
        assertThat(docs).contains("actorid");
        assertThat(docs).contains("not idempotent");
        assertThat(docs).contains("must not blindly retry");
        assertThat(docs).contains("linkedalertids");
        assertThat(docs).contains("transactionids");
    }

    @Test
    void fdp42DocsShouldNotMakePositiveFinalityOrReplayClaims() throws IOException {
        String docs = readDocs().toLowerCase(java.util.Locale.ROOT);

        assertThat(docs)
                .doesNotContain("is evidence-gated")
                .doesNotContain("are evidence-gated")
                .doesNotContain("is replay safe")
                .doesNotContain("are replay safe")
                .doesNotContain("is lease fenced")
                .doesNotContain("are lease fenced")
                .doesNotContain("provides external finality")
                .doesNotContain("guarantees exactly-once");
    }

    private String readDocs() throws IOException {
        StringBuilder builder = new StringBuilder();
        for (String file : FDP42_DOCS) {
            Path path = resolve(file);
            builder.append(Files.readString(path)).append('\n');
        }
        return builder.toString();
    }

    private Path resolve(String file) {
        Path fromRoot = Path.of(file);
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..").resolve(file);
    }
}
