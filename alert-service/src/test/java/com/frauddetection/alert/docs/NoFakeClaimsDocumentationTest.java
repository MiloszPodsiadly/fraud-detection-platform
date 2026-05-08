package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class NoFakeClaimsDocumentationTest {

    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "production enabled",
            "production ready",
            "bank certified",
            "production certified",
            "externally final",
            "external finality guaranteed",
            "distributed ACID achieved",
            "exactly-once Kafka guaranteed",
            "WORM guaranteed",
            "legal notarization",
            "signed provenance proves business correctness",
            "signed artifact proves business correctness",
            "release approval proves correctness",
            "checkpoint renewal proves progress",
            "lease renewal proves progress",
            "fixture image is release image",
            "fixture proof is production image proof",
            "runtime checkpoint fixture is production image proof",
            "RUNTIME_REACHED_TEST_FIXTURE is RUNTIME_REACHED_PRODUCTION_IMAGE",
            "READY_FOR_ENABLEMENT_REVIEW means PRODUCTION_ENABLED",
            "recovery required is success",
            "local evidence confirmation is external confirmation",
            "FINALIZED_VISIBLE means externally confirmed"
    );

    private static final List<String> ALLOWED_NEGATIVE_CONTEXT = List.of(
            "forbidden",
            "forbidden claim",
            "forbidden claims",
            "incorrect",
            "no-go",
            "non-claim",
            "non-claims",
            "does not claim",
            "does not mean",
            "does not provide",
            "does not prove",
            "does not represent",
            "must not",
            "not ",
            "no ",
            "never",
            "future",
            "unsupported claim",
            "is not",
            "are not"
    );

    @Test
    void currentDocumentationDoesNotContainPositiveFakeClaims() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path path : scannedMarkdown()) {
            String content = Files.readString(path);
            String lowerContent = content.toLowerCase(Locale.ROOT);
            String[] lines = content.split("\\R", -1);
            for (String phrase : FORBIDDEN_PHRASES) {
                String lowerPhrase = phrase.toLowerCase(Locale.ROOT);
                int index = lowerContent.indexOf(lowerPhrase);
                while (index >= 0) {
                    if (!isAllowedNegativeContext(lowerContent, index, lowerPhrase.length())) {
                        int lineNumber = lineNumberAt(content, index);
                        String context = surroundingLineContext(lines, lineNumber);
                        violations.add("%s:%d: forbidden positive claim '%s': %s".formatted(
                                DocumentationTestSupport.relativeToRepository(path),
                                lineNumber,
                                phrase,
                                context
                        ));
                    }
                    index = lowerContent.indexOf(lowerPhrase, index + lowerPhrase.length());
                }
            }
        }

        assertThat(violations)
                .as("Docs must not contain fake or overbroad positive claims")
                .isEmpty();
    }

    private boolean isAllowedNegativeContext(String lowerContent, int index, int length) {
        int start = Math.max(0, index - 220);
        int end = Math.min(lowerContent.length(), index + length + 220);
        String context = lowerContent.substring(start, end);
        return ALLOWED_NEGATIVE_CONTEXT.stream().anyMatch(context::contains);
    }

    private String surroundingLineContext(String[] lines, int lineNumber) {
        int start = Math.max(0, lineNumber - 2);
        int end = Math.min(lines.length, lineNumber + 1);
        return String.join(" / ", java.util.Arrays.copyOfRange(lines, start, end)).trim();
    }

    private int lineNumberAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private List<Path> scannedMarkdown() throws Exception {
        List<Path> files = new ArrayList<>();
        Path repositoryRoot = DocumentationTestSupport.repositoryRoot();
        files.add(repositoryRoot.resolve("README.md"));
        try (Stream<Path> stream = Files.walk(repositoryRoot.resolve("docs"))) {
            files.addAll(stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .toList());
        }
        Path templateRoot = repositoryRoot.resolve(".github/PULL_REQUEST_TEMPLATE");
        if (Files.exists(templateRoot)) {
            try (Stream<Path> stream = Files.walk(templateRoot)) {
                files.addAll(stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".md"))
                        .toList());
            }
        }
        return files;
    }
}
