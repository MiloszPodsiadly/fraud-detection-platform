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
        JsonNode inventory = OBJECT_MAPPER.readTree(
                Files.readString(DocumentationTestSupport.docsRoot().resolve("documentation-inventory.json"))
        );
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
        try (Stream<Path> stream = Files.walk(DocumentationTestSupport.docsRoot())) {
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

    @Test
    void currentTruthAndHistoricalEvidenceAreExplicitlySeparated() throws Exception {
        String index = Files.readString(DocumentationTestSupport.docsRoot().resolve("index.md"));
        String audit = Files.readString(DocumentationTestSupport.docsRoot().resolve("documentation-audit.md"));

        assertThat(index)
                .contains("Current Source Of Truth")
                .contains("Current API, Config, And Security Semantics")
                .contains("Documentation Governance")
                .contains("Historical FDP Evidence")
                .contains("Release And Governance Proof Artifacts")
                .contains("Templates And Checklists")
                .contains("Superseded Or Historical Context")
                .contains("FDP-38 fixture proof is test-fixture runtime evidence only")
                .contains("FDP-40 signed provenance readiness is readiness evidence only")
                .contains("`READY_FOR_ENABLEMENT_REVIEW` never means `PRODUCTION_ENABLED`");
        assertThat(audit)
                .contains("Current source of truth")
                .contains("Contract summary")
                .contains("Historical FDP evidence")
                .contains("Current API, Config, And Security Semantics")
                .contains("Historical Evidence Handling")
                .contains("Release And Governance Proof Artifacts")
                .contains("Templates And Checklists")
                .contains("Superseded Or Historical Context")
                .contains("FDP-38")
                .contains("not production-image proof")
                .contains("FDP-40")
                .contains("not enforced signing by itself")
                .contains("`READY_FOR_ENABLEMENT_REVIEW` never means `PRODUCTION_ENABLED`");
    }

    private List<String> normalizedSegments(String path) {
        return List.of(path.replace('\\', '/').split("/"));
    }
}
