package com.frauddetection.alert.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationEvidencePlatformCleanupDocsTest {

    @Test
    void InvestigationEvidencePlatformRoadmapDocsTest() throws Exception {
        String docs = roadmapDocs();

        assertThat(docs)
                .contains("FDP-73")
                .contains("FDP-74")
                .contains("FDP-75")
                .contains("FDP-76")
                .contains("FDP-77")
                .contains("FDP-78")
                .contains("FDP-79")
                .contains("FDP-80")
                .contains("FDP-81")
                .contains("Evidence Summary")
                .contains("Evidence Timeline")
                .contains("read-surface guardrails")
                .contains("read-model observability")
                .contains("post-milestone read-surface composition cleanup")
                .contains("FDP-81 performs full cleanup after the evidence platform milestone");
    }

    @Test
    void InvestigationEvidencePlatformCleanupInventoryDocsTest() throws Exception {
        String docs = inventoryDocs();

        assertThat(docs)
                .contains("No delete without proof")
                .contains("Legacy does not mean unused")
                .contains("Domain compatibility")
                .contains("Migration/release docs")
                .contains("Negative regression guard")
                .contains("Current compatibility state")
                .contains("Confirmed unused removal candidate")
                .contains("Do-not-delete")
                .contains("Replacement/current owner")
                .contains("Tests proving no behavior change");
    }

    @Test
    void CleanupInventoryNoDeleteWithoutProofTest() throws Exception {
        String docs = inventoryDocs();

        assertThat(docs)
                .contains("No delete without proof")
                .contains("No delete by name only")
                .contains("Delete only if search/import proof shows no active reference")
                .contains("No deletions approved in FDP-81 initial cleanup pass");
    }

    @Test
    void CleanupInventoryLegacyDoesNotMeanUnusedTest() throws Exception {
        String docs = inventoryDocs();

        assertThat(docs)
                .contains("Legacy does not mean unused")
                .contains("Do not treat LEGACY states as dead code")
                .doesNotContain("all legacy can be deleted")
                .doesNotContain("LEGACY states are dead code");
    }

    @Test
    void CleanupInventoryDoNotDeleteDomainLegacyStatesTest() throws Exception {
        String docs = inventoryDocs();

        assertThat(docs)
                .contains("Evidence Summary `LEGACY` state")
                .contains("Evidence Timeline `LEGACY_CONTEXT` event")
                .contains("LEGACY states used by Evidence Summary / Timeline")
                .contains("Active compatibility state");
    }

    @Test
    void CleanupInventoryRequiresReplacementOwnerTest() throws Exception {
        String docs = inventoryDocs();

        assertThat(docs)
                .contains("Replacement/current owner")
                .contains("Delete only if replacement/current owner is documented")
                .contains("no-owner-needed is explicitly justified");
    }

    @Test
    void CleanupInventoryClassifiesNegativeRegressionGuardsTest() throws Exception {
        String docs = inventoryDocs();

        assertThat(docs)
                .contains("Negative regression guard")
                .contains("negative regression tests protecting old unsafe paths")
                .contains("source guards protecting raw payload / raw identifier boundaries")
                .contains("malicious fixtures used by safety tests")
                .doesNotContain("regression guards can be deleted without replacement");
    }

    @Test
    void CleanupInventoryListsDeletedItemsTest() throws Exception {
        String docs = inventoryDocs();

        assertThat(docs)
                .contains("| Path | Type | Why unused | Search proof | Import/reference proof | Replacement/current owner | Risk | Tests proving no behavior change | Delete in FDP-81 |")
                .contains("No deletions approved in FDP-81 initial cleanup pass")
                .contains("There are no confirmed unused removal candidates in the FDP-81 initial cleanup pass");
    }

    @Test
    void CleanupInventoryDoesNotAllowReleaseDocsDeletionByDefaultTest() throws Exception {
        String docs = inventoryDocs();

        assertThat(docs)
                .contains("release docs documenting historical migrations")
                .contains("Do not delete release docs by default")
                .doesNotContain("release docs can be deleted by default");
    }

    @Test
    void RoadmapDoesNotCreateProductScopeTest() throws Exception {
        String docs = roadmapDocs();

        assertThat(docs)
                .contains("These are candidates only. FDP-81 does not implement them")
                .contains("does not implement a new runtime feature")
                .contains("must open explicit product scope before adding tabs, drilldowns, final outcome UX")
                .doesNotContain("FDP-81 implements Final Outcome Semantics")
                .doesNotContain("FDP-81 implements False Positive Management");
    }

    private String roadmapDocs() throws Exception {
        return Files.readString(Path.of("..", "docs", "product", "investigation_evidence_platform_roadmap.md"));
    }

    private String inventoryDocs() throws Exception {
        return Files.readString(Path.of("..", "docs", "product", "investigation_evidence_platform_cleanup_inventory.md"));
    }
}
