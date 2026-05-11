package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp44FraudCaseIdempotencyOperationalDocsNoOverclaimTest {

    private static final List<String> DOCS = List.of(
            "docs/api/fraud-case-api.md",
            "docs/fdp-43-merge-gate.md",
            "docs/fdp-44-merge-gate.md",
            "docs/runbooks/fraud-case-lifecycle-idempotency.md",
            "docs/observability/fraud-case-lifecycle-idempotency-dashboard.md"
    );

    private static final List<String> FORBIDDEN_CLAIMS = List.of(
            "global exactly-once",
            "exactly-once delivery",
            "kafka/outbox exactly-once",
            "distributed ACID",
            "external finality",
            "FDP-29 finality",
            "lease fencing",
            "lease-fenced",
            "WORM storage",
            "legal notarization",
            "bank certification",
            "deterministic concurrent response",
            "deterministic concurrent response ordering"
    );

    @Test
    void operationalDocsStateLocalScopeRetentionAndConcurrencyLimits() throws IOException {
        String docs = docs().toLowerCase(Locale.ROOT);

        assertThat(docs)
                .contains("local fraud-case lifecycle idempotency")
                .contains("requires `x-idempotency-key`")
                .contains("same key")
                .contains("same-key same-claim")
                .contains("does not create duplicate mutation/audit/idempotency record")
                .contains("identical concurrent response timing is not guaranteed")
                .contains("after retention and eventual mongo ttl cleanup")
                .contains("may execute as a new local lifecycle operation")
                .contains("do not manually edit")
                .contains("low-cardinality")
                .contains("unknown `dataaccessexception`")
                .contains("unknown `transactionsystemexception`");
    }

    @Test
    void operationalDocsKeepUnsupportedClaimsNegative() throws IOException {
        String docs = docs();

        for (String claim : FORBIDDEN_CLAIMS) {
            assertClaimIsNegativeContext(docs, claim);
        }
    }

    private void assertClaimIsNegativeContext(String source, String claim) {
        String lowerSource = source.toLowerCase(Locale.ROOT);
        String lowerClaim = claim.toLowerCase(Locale.ROOT);
        int index = lowerSource.indexOf(lowerClaim);
        while (index >= 0) {
            int start = Math.max(0, index - 220);
            int end = Math.min(lowerSource.length(), index + lowerClaim.length() + 220);
            String context = lowerSource.substring(start, end);
            assertThat(context)
                    .as("FDP-44 overclaim must be negative or NO-GO contextual: " + claim)
                    .containsAnyOf(
                            "does not claim",
                            "does not",
                            "not ",
                            "no ",
                            "no-go",
                            "must not",
                            "is not",
                            "do not claim",
                            "unsupported",
                            "forbidden"
                    );
            index = lowerSource.indexOf(lowerClaim, index + lowerClaim.length());
        }
    }

    private String docs() throws IOException {
        StringBuilder builder = new StringBuilder();
        for (String file : DOCS) {
            Path path = resolve(file);
            builder.append("\n# ").append(file).append('\n');
            builder.append(Files.readString(path));
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
