package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationNamingConventionTest {

    private static final Set<String> ALLOWED_SPECIAL_NAMES = Set.of(
            "README.md",
            "alert-service.openapi.yaml",
            "ml-inference-service.openapi.yaml"
    );

    private static final List<String> CURRENT_DOC_FOLDERS = List.of(
            "../docs/api",
            "../docs/architecture",
            "../docs/configuration",
            "../docs/deployment",
            "../docs/ml",
            "../docs/observability",
            "../docs/security"
    );

    @Test
    void currentDocumentationUsesConsistentLowerKebabNames() throws Exception {
        for (String folder : CURRENT_DOC_FOLDERS) {
            Path root = Path.of(folder);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path path : stream.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".md"))
                        .toList()) {
                    String fileName = path.getFileName().toString();
                    if (ALLOWED_SPECIAL_NAMES.contains(fileName)) {
                        continue;
                    }
                    assertThat(fileName)
                            .as("Current docs should use lower-kebab names: " + path)
                            .matches("[a-z0-9]+(?:-[a-z0-9]+)*\\.md");
                }
            }
        }
    }

    @Test
    void styleGuideDocumentsLowerKebabForHistoricalFdpFiles() throws Exception {
        String guide = Files.readString(Path.of("../docs/documentation-style-guide.md"));
        String map = Files.readString(Path.of("../docs/documentation-naming-map.md"));

        assertThat(guide)
                .contains("Historical FDP docs")
                .contains("filenames use lowercase")
                .contains("`fdp-*` form");
        assertThat(map)
                .contains("Moved Current Docs")
                .contains("lower-kebab")
                .contains("Renaming documentation does not change API behavior");
    }
}


