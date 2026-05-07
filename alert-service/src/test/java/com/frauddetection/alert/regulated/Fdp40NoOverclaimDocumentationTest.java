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

class Fdp40NoOverclaimDocumentationTest {

    private static final List<String> FORBIDDEN_POSITIVE_CLAIMS = List.of(
            "PRODUCTION_ENABLED",
            "BANK_CERTIFIED",
            "production certified",
            "external finality",
            "distributed ACID",
            "global transaction",
            "exactly-once Kafka",
            "legal notarization",
            "WORM guarantee",
            "signed artifact proves business correctness",
            "signed artifact proves runtime correctness",
            "release approval proves operational correctness",
            "registry immutability proves ACID",
            "SLSA attestation proves bank certification"
    );

    @Test
    void fdp40DocsTemplatesAndGeneratedArtifactsDoNotContainPositiveOverclaims() throws Exception {
        String combined = readTree(Path.of("../docs/release"), "FDP-40", ".md")
                + "\n" + readTree(Path.of("../docs/adr"), "FDP-40", ".md")
                + "\n" + readTree(Path.of("target/fdp40-release"), "fdp40-", ".md")
                + "\n" + readTree(Path.of("target/fdp40-release"), "fdp40-", ".json")
                + "\n" + readTree(Path.of("../.github/PULL_REQUEST_TEMPLATE"), "fdp-enablement", ".md");

        assertThat(combined)
                .contains("READY_FOR_ENABLEMENT_REVIEW is not PRODUCTION_ENABLED")
                .contains("does not claim external finality")
                .contains("does not mean distributed ACID")
                .contains("readiness is not full platform enforcement");
        for (String claim : FORBIDDEN_POSITIVE_CLAIMS) {
            assertClaimIsNegativeContext(combined, claim);
        }
    }

    private String readTree(Path root, String namePrefix, String suffix) {
        if (!Files.exists(root)) {
            return "";
        }
        try (Stream<Path> stream = Files.walk(root)) {
            StringBuilder builder = new StringBuilder();
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith(namePrefix))
                    .filter(file -> file.toString().endsWith(suffix))
                    .toList()) {
                builder.append("\n# ").append(path).append("\n");
                builder.append(Files.readString(path));
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to scan FDP-40 docs/artifacts", exception);
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
                    .as("FDP-40 overclaim must be negative/future/NO-GO contextual: " + claim)
                    .containsAnyOf(
                            "does not claim",
                            "does not mean",
                            "does not",
                            "not ",
                            "no ",
                            "future",
                            "unsupported claim",
                            "no-go",
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
