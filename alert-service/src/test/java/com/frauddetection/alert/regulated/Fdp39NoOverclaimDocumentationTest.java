package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp39NoOverclaimDocumentationTest {

    private static final List<String> FORBIDDEN_POSITIVE_CLAIMS = List.of(
            "PRODUCTION_ENABLED",
            "BANK_CERTIFIED",
            "production certified",
            "production deployable fixture",
            "fixture proof is production proof",
            "RUNTIME_REACHED_PRODUCTION_IMAGE",
            "distributed ACID",
            "global transaction",
            "exactly-once Kafka",
            "external finality",
            "legal notarization",
            "WORM guarantee",
            "full production config certification",
            "automatic FDP-29 enablement"
    );

    @Test
    void releaseDocsAndGeneratedProofArtifactsDoNotContainPositiveOverclaims() throws Exception {
        String combined = readMarkdownTree(Path.of("../docs"));
        Path generated = Path.of("target", "fdp39-governance");
        if (Files.exists(generated)) {
            combined += "\n" + readMarkdownTree(generated);
            combined += "\n" + readJsonTree(generated);
        }

        assertThat(combined).contains("READY_FOR_ENABLEMENT_REVIEW` is not `PRODUCTION_ENABLED");
        assertThat(combined).contains("Fixture proof is not production proof");
        assertThat(combined).doesNotContain("enablemenance");
        for (String claim : FORBIDDEN_POSITIVE_CLAIMS) {
            assertClaimIsNegativeContext(combined, claim);
        }
    }

    private String readMarkdownTree(Path root) {
        return readTree(root, ".md");
    }

    private String readJsonTree(Path root) {
        return readTree(root, ".json");
    }

    private String readTree(Path root, String suffix) {
        if (!Files.exists(root)) {
            return "";
        }
        try (Stream<Path> stream = Files.walk(root)) {
            StringBuilder builder = new StringBuilder();
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(suffix))
                    .toList()) {
                builder.append("\n# ").append(path).append("\n");
                builder.append(Files.readString(path));
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to scan FDP-39 docs/artifacts", exception);
        }
    }

    private void assertClaimIsNegativeContext(String source, String claim) {
        String lowerSource = source.toLowerCase(Locale.ROOT);
        String lowerClaim = claim.toLowerCase(Locale.ROOT);
        int index = lowerSource.indexOf(lowerClaim);
        while (index >= 0) {
            int start = Math.max(0, index - 360);
            int end = Math.min(lowerSource.length(), index + lowerClaim.length() + 360);
            String context = lowerSource.substring(start, end);
            assertThat(context)
                    .as("FDP-39 overclaim must be negative/future/NO-GO contextual: " + claim)
                    .containsAnyOf(
                            "does not claim",
                            "does not",
                            "not ",
                            "no ",
                            "future scope",
                            "forbidden",
                            "no-go",
                            "claims forbidden",
                            "non-goals",
                            "must not",
                            "is not",
                            "_false",
                            ": false",
                            "\" : false"
                    );
            index = lowerSource.indexOf(lowerClaim, index + lowerClaim.length());
        }
    }
}
