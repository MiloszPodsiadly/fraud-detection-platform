package com.frauddetection.alert.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationReadabilityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<Path> CURRENT_DOCS = List.of(
            Path.of("docs/index.md"),
            Path.of("docs/documentation_audit.md"),
            Path.of("docs/api/public_api_semantics.md"),
            Path.of("docs/configuration/configuration_guide.md"),
            Path.of("docs/documentation_cleanup_merge_gate.md"),
            Path.of("docs/documentation_style_guide.md")
    );

    private static final List<String> FORBIDDEN_BLOB_PREFIXES = List.of(
            "# Documentation Index Status:",
            "# Documentation Audit Status:",
            "# Public API Semantics Status:",
            "# Configuration Guide Status:",
            "# Documentation Cleanup Merge Gate Status:",
            "# Documentation Style Guide Status:"
    );

    @Test
    void currentDocumentationIsReadableAndReviewable() throws Exception {
        Path repositoryRoot = DocumentationTestSupport.repositoryRoot();
        for (Path relativePath : CURRENT_DOCS) {
            Path path = repositoryRoot.resolve(relativePath);
            assertThat(Files.exists(path)).as("Missing current doc: " + relativePath).isTrue();
            String content = Files.readString(path);
            assertThat(content).as(path + " must include status").contains("Status:");
            assertThat(content).as(path + " must name scope").contains("Scope");
            assertThat(readabilityViolations(relativePath, content))
                    .as(relativePath + " must be readable raw Markdown")
                    .isEmpty();
        }
    }

    @Test
    void knownOneLineDocumentationIndexBlobIsRejected() {
        String oldBadPattern = "# Documentation Index Status: current documentation index. ## Scope";

        assertThat(readabilityViolations(Path.of("docs/index.md"), oldBadPattern))
                .as("DocumentationReadabilityTest must reject the exact old one-line blob pattern")
                .isNotEmpty();
    }

    @Test
    void jsonDocumentationArtifactsParse() throws Exception {
        try (Stream<Path> stream = Files.walk(DocumentationTestSupport.docsRoot())) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".json"))
                    .toList()) {
                OBJECT_MAPPER.readTree(Files.readString(path));
            }
        }
    }

    @Test
    void openApiAndYamlDocsRemainStructured() throws Exception {
        List<Path> yamlDocs = List.of(
                DocumentationTestSupport.docsRoot().resolve("openapi/alert_service.openapi.yaml"),
                DocumentationTestSupport.docsRoot().resolve("openapi/ml_inference_service.openapi.yaml"),
                DocumentationTestSupport.docsRoot().resolve("release/fdp_40_release_manifest_template.yaml")
        );
        for (Path path : yamlDocs) {
            String content = Files.readString(path);
            assertThat(content).as(path + " must not be minified").contains("\n");
            assertThat(content).as(path + " must avoid tab indentation").doesNotContain("\t");
            assertThat(content).as(path + " must contain key-value YAML structure").contains(":");
        }
        assertThat(Files.readString(DocumentationTestSupport.docsRoot().resolve("openapi/alert_service.openapi.yaml")))
                .contains("openapi: 3.0.3")
                .contains("This portfolio API specification does not represent bank certification");
        assertThat(Files.readString(DocumentationTestSupport.docsRoot().resolve("openapi/ml_inference_service.openapi.yaml")))
                .contains("openapi: 3.0.3")
                .contains("This portfolio API specification does not represent bank certification");
    }

    private List<String> readabilityViolations(Path path, String content) {
        List<String> violations = new ArrayList<>();
        boolean inCodeBlock = false;
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
            }
            if (inCodeBlock) {
                continue;
            }
            int lineNumber = index + 1;
            if (!line.startsWith("http") && !line.startsWith("|") && line.length() > 180) {
                violations.add("%s:%d line length %d exceeds 180: %s".formatted(
                        path, lineNumber, line.length(), excerpt(line)
                ));
            }
            for (String forbiddenPrefix : FORBIDDEN_BLOB_PREFIXES) {
                if (line.contains(forbiddenPrefix)) {
                    violations.add("%s:%d contains one-line blob prefix %s: %s".formatted(
                            path, lineNumber, forbiddenPrefix, excerpt(line)
                    ));
                }
            }
            if (line.contains("## Scope ") && !line.stripLeading().startsWith("## Scope ")) {
                violations.add("%s:%d contains embedded Scope heading: %s".formatted(
                        path, lineNumber, excerpt(line)
                ));
            }
            if (line.matches(".*\\S\\s+#{1,6}\\s+.*")) {
                violations.add("%s:%d contains heading marker after non-whitespace text: %s".formatted(
                        path, lineNumber, excerpt(line)
                ));
            }
            if (markdownHeadingCount(line) > 1) {
                violations.add("%s:%d contains multiple Markdown headings on one line: %s".formatted(
                        path, lineNumber, excerpt(line)
                ));
            }
        }
        return violations;
    }

    private int markdownHeadingCount(String line) {
        int count = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == '#'
                    && (index == 0 || Character.isWhitespace(line.charAt(index - 1)))
                    && (index == 0 || line.charAt(index - 1) != '#')) {
                int end = index;
                while (end < line.length() && line.charAt(end) == '#') {
                    end++;
                }
                if (end <= index + 6 && end < line.length() && Character.isWhitespace(line.charAt(end))) {
                    count++;
                }
                index = end;
            }
        }
        return count;
    }

    private String excerpt(String line) {
        String normalized = line.replace("\t", "\\t");
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 217) + "...";
    }
}
