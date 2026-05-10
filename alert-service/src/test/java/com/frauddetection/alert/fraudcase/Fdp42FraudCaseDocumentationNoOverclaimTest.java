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
            "docs/fdp-42-summary.md",
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
        assertThat(docs).contains("post /fraud-cases");
        assertThat(docs).contains("post /fraud-cases/{caseid}/assign");
        assertThat(docs).contains("post /fraud-cases/{caseid}/notes");
        assertThat(docs).contains("post /fraud-cases/{caseid}/decisions");
        assertThat(docs).contains("post /fraud-cases/{caseid}/transition");
        assertThat(docs).contains("post /fraud-cases/{caseid}/close");
        assertThat(docs).contains("post /fraud-cases/{caseid}/reopen");
        assertThat(docs).contains("linkedalertids");
        assertThat(docs).contains("transactionids");
        assertThat(docs).contains("system-generated case candidate ingestion");
        assertThat(docs).contains("resolved");
        assertThat(docs).contains("fdp42-fraud-case-management");
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

    @Test
    void fdp42DocsShouldQualifyAuditScopeToAnalystLifecycleMutations() throws IOException {
        String docs = readDocs().toLowerCase(java.util.Locale.ROOT);

        assertThat(docs).contains("every analyst lifecycle mutation writes");
        assertThat(docs).contains("system event ingestion");
        assertThat(docs).doesNotContain("every case mutation writes");
        assertThat(docs).doesNotContain("every case mutation is audited");
        assertThat(docs).contains("not worm storage");
        assertThat(docs).contains("not an analyst lifecycle mutation");
        assertThat(docs).contains("not analyst lifecycle mutation");
    }

    @Test
    void fdp42DocsShouldScopeAtomicRollbackToRequiredMongoTransactions() throws IOException {
        String docs = readDocs().toLowerCase(java.util.Locale.ROOT);

        assertThat(docs).contains("mongo transactions");
        assertThat(docs).contains("transaction-mode=required");
        assertThat(docs).contains("mongotransactionmanager");
        assertThat(docs).contains("if transaction mode is `off`, fdp-42 must not claim rollback");
        assertThat(docs).contains("atomicity");
        assertThat(docs).doesNotContain("unconditional atomic rollback");
        assertThat(docs).doesNotContain("rollback atomicity regardless of transaction mode");
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
