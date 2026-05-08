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
            Path.of("../docs/index.md"),
            Path.of("../docs/documentation-audit.md"),
            Path.of("../docs/architecture/current-architecture.md"),
            Path.of("../docs/api/public-api-semantics.md"),
            Path.of("../docs/api/status-truth-table.md"),
            Path.of("../docs/api/openapi-safety-audit.md"),
            Path.of("../docs/configuration/configuration-guide.md"),
            Path.of("../docs/architecture/diagrams.md"),
            Path.of("../docs/runbooks/index.md"),
            Path.of("../docs/historical-fdp-documents.md"),
            Path.of("../docs/documentation-cleanup-merge-gate.md")
    );

    @Test
    void currentDocumentationIsReadableAndReviewable() throws Exception {
        for (Path path : CURRENT_DOCS) {
            assertThat(Files.exists(path)).as("Missing current doc: " + path).isTrue();
            String content = Files.readString(path);
            assertThat(content).as(path + " must include status").contains("Status:");
            assertThat(content).as(path + " must name scope").contains("Scope");
            assertMarkdownLineLength(path, content);
        }
    }

    @Test
    void jsonDocumentationArtifactsParse() throws Exception {
        try (Stream<Path> stream = Files.walk(Path.of("../docs"))) {
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
                Path.of("../docs/openapi/alert-service.openapi.yaml"),
                Path.of("../docs/openapi/ml-inference-service.openapi.yaml"),
                Path.of("../docs/release/fdp-40-release-manifest-template.yaml")
        );
        for (Path path : yamlDocs) {
            String content = Files.readString(path);
            assertThat(content).as(path + " must not be minified").contains("\n");
            assertThat(content).as(path + " must avoid tab indentation").doesNotContain("\t");
            assertThat(content).as(path + " must contain key-value YAML structure").contains(":");
        }
        assertThat(Files.readString(Path.of("../docs/openapi/alert-service.openapi.yaml")))
                .contains("openapi: 3.0.3")
                .contains("This portfolio API specification does not represent bank certification");
        assertThat(Files.readString(Path.of("../docs/openapi/ml-inference-service.openapi.yaml")))
                .contains("openapi: 3.0.3")
                .contains("This portfolio API specification does not represent bank certification");
    }

    private void assertMarkdownLineLength(Path path, String content) {
        boolean inCodeBlock = false;
        for (String line : content.split("\\R", -1)) {
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
            }
            if (!inCodeBlock && !line.startsWith("http")) {
                assertThat(line.length())
                        .as("Line too long in " + path + ": " + line)
                        .isLessThanOrEqualTo(240);
            }
        }
    }
}
