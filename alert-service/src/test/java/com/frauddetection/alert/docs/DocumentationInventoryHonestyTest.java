package com.frauddetection.alert.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationInventoryHonestyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void inventoryExcludesPromptSourcesBuildOutputDependenciesAndGitInternals() throws Exception {
        JsonNode inventory = OBJECT_MAPPER.readTree(Files.readString(Path.of("../docs/documentation-inventory.json")));
        List<String> paths = new ArrayList<>();
        inventory.path("artifacts").forEach(node -> paths.add(node.path("path").asText()));

        assertThat(inventory.path("total_artifacts").asInt()).isEqualTo(paths.size());
        assertThat(paths).isNotEmpty();
        assertThat(paths)
                .allSatisfy(path -> assertThat(normalizedSegments(path))
                        .doesNotContain("documents")
                        .doesNotContain("node_modules")
                        .doesNotContain("target")
                        .doesNotContain(".git")
                        .doesNotContain("tmpa1g3iqhx")
                        .doesNotContain("tmpch6xfio1"));
    }

    @Test
    void currentDocumentationDoesNotEmbedActiveBranchAsStatus() throws Exception {
        try (Stream<Path> stream = Files.walk(Path.of("../docs"))) {
            List<Path> currentDocs = stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md") || path.toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().startsWith("fdp-"))
                    .toList();
            for (Path path : currentDocs) {
                assertThat(Files.readString(path))
                        .as("Current documentation must not age by naming the active branch: " + path)
                        .doesNotContain("FDP-41");
            }
        }
    }

    private List<String> normalizedSegments(String path) {
        return List.of(path.replace('\\', '/').split("/"));
    }
}
