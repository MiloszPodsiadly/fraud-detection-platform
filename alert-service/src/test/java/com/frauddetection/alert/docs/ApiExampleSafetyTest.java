package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExampleSafetyTest {

    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("(?i)-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)\\\"password\\\"\\s*:\\s*\\\"password\\\""),
            Pattern.compile("(?i)\\\"secret\\\"\\s*:\\s*\\\"secret\\\""),
            Pattern.compile("(?i)\\\"token\\\"\\s*:\\s*\\\"token\\\""),
            Pattern.compile("\\bat [a-zA-Z0-9_.]+\\([^)]*\\.java:\\d+\\)")
    );

    @Test
    void publicDocsDoNotContainUnsafeExampleSecretsOrStackTraces() throws Exception {
        for (Path path : scannedDocs()) {
            String content = Files.readString(path);
            for (Pattern pattern : FORBIDDEN_PATTERNS) {
                assertThat(pattern.matcher(content).find())
                        .as("Unsafe public example pattern " + pattern + " in " + path)
                        .isFalse();
            }
            assertSensitiveAssignmentIsRedacted(path, content, "idempotencyKey");
            assertSensitiveAssignmentIsRedacted(path, content, "idempotency_key");
            assertSensitiveAssignmentIsRedacted(path, content, "requestHash");
            assertSensitiveAssignmentIsRedacted(path, content, "request_hash");
            assertSensitiveAssignmentIsRedacted(path, content, "leaseOwner");
            assertSensitiveAssignmentIsRedacted(path, content, "lease_owner");
        }
    }

    private List<Path> scannedDocs() throws Exception {
        try (Stream<Path> stream = Files.walk(Path.of("../docs"))) {
            List<Path> docs = stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md")
                            || path.toString().endsWith(".yaml")
                            || path.toString().endsWith(".yml")
                            || path.toString().endsWith(".json"))
                    .toList();
            java.util.ArrayList<Path> all = new java.util.ArrayList<>(docs);
            all.add(Path.of("../README.md"));
            return all;
        }
    }

    private void assertSensitiveAssignmentIsRedacted(Path path, String content, String field) {
        Pattern assignment = Pattern.compile("(?i)[\\\"']?" + Pattern.quote(field)
                + "[\\\"']?\\s*[:=]\\s*[\\\"']([^\\\"']+)[\\\"']");
        var matcher = assignment.matcher(content);
        while (matcher.find()) {
            String value = matcher.group(1);
            assertThat(value)
                    .as("Sensitive example value must be redacted/example in " + path + " for " + field)
                    .containsAnyOf("redacted", "example", "dummy", "<", "***");
        }
    }
}

