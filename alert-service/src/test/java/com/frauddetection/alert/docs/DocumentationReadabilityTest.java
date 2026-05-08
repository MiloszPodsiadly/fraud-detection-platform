package com.frauddetection.alert.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationReadabilityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<Path> CURRENT_DOCS = List.of(
            Path.of("docs/index.md"),
            Path.of("docs/documentation-audit.md"),
            Path.of("docs/api/public-api-semantics.md"),
            Path.of("docs/configuration/configuration-guide.md"),
            Path.of("docs/documentation-cleanup-merge-gate.md"),
            Path.of("docs/documentation-style-guide.md")
    );

    private static final List<String> FORBIDDEN_BLOB_PREFIXES = List.of(
            "# Documentation Index Status:",
            "# Documentation Audit Status:",
            "# Public API Semantics Status:",
            "# Configuration Guide Status:"
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
            assertMarkdownLineLength(path, content);
            assertThat(content)
                    .as(path + " must not contain huge one-line Markdown blobs")
                    .doesNotContain("## Scope ");
            for (String forbiddenPrefix : FORBIDDEN_BLOB_PREFIXES) {
                assertThat(content)
                        .as(path + " must not contain one-line blob prefix: " + forbiddenPrefix)
                        .doesNotContain(forbiddenPrefix);
            }
        }
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
                DocumentationTestSupport.docsRoot().resolve("openapi/alert-service.openapi.yaml"),
                DocumentationTestSupport.docsRoot().resolve("openapi/ml-inference-service.openapi.yaml"),
                DocumentationTestSupport.docsRoot().resolve("release/fdp-40-release-manifest-template.yaml")
        );
        for (Path path : yamlDocs) {
            String content = Files.readString(path);
            assertThat(content).as(path + " must not be minified").contains("\n");
            assertThat(content).as(path + " must avoid tab indentation").doesNotContain("\t");
            assertThat(content).as(path + " must contain key-value YAML structure").contains(":");
        }
        assertThat(Files.readString(DocumentationTestSupport.docsRoot().resolve("openapi/alert-service.openapi.yaml")))
                .contains("openapi: 3.0.3")
                .contains("This portfolio API specification does not represent bank certification");
        assertThat(Files.readString(DocumentationTestSupport.docsRoot().resolve("openapi/ml-inference-service.openapi.yaml")))
                .contains("openapi: 3.0.3")
                .contains("This portfolio API specification does not represent bank certification");
    }

    private void assertMarkdownLineLength(Path path, String content) {
        boolean inCodeBlock = false;
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
            }
            if (!inCodeBlock && !line.startsWith("http") && !line.startsWith("|")) {
                assertThat(line.length())
                        .as("Line too long in " + path + " line " + (index + 1) + ": " + line)
                        .isLessThanOrEqualTo(180);
            }
        }
    }
}
