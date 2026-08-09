package com.frauddetection.scoring.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RulesV1ArchitectureDocumentationGuardTest {

    @Test
    void documentsFinalRulesV1CompatibilityDecision() throws Exception {
        String docs = targetDocuments();

        assertThat(docs)
                .contains("fdp-129 keeps rules as `rule-based-engine` / `v1`")
                .contains("diagnostic adapter remains `rules.primary` / `1.0.0`")
                .contains("rules v1 compatibility preserves the historical contribution actually present")
                .contains("current canonical producer payloads still publish `rule-based-engine` / `v1`")
                .contains("historical partial representations do not receive unavailable components")
                .contains("canonical false is authoritative")
                .contains("present-invalid canonical facts and present-invalid top-level temporal facts fail closed")
                .contains("top-level `recenttransactioncount` can contribute only with `recenttransactioncountwindow=pt1m`")
                .contains("top-level `recentamountsum` can contribute only with `recentamountsumwindow=pt1m`")
                .contains("supported monetary currencies for ingest and enrichment are `pln`, `eur`, `usd`, and `gbp`")
                .contains("unknown currencies are never converted as pln")
                .contains("primary scoring and diagnostic engine intelligence have different failure semantics")
                .contains("diagnostic `rulebasedsignalengine` reports bounded `degraded`")
                .contains("legacy rules v1 compatibility is retained only for replay and rolling-deployment safety")
                .contains("future removal of legacy flags")
                .contains("introduce a clean canonical rules v2 through explicit version migration")
                .contains("fdp-129 does not implement that future policy")
                .contains("rules_v1_compatibility_matrix.json");
    }

    @Test
    void preventsRulesV1DocumentationOverclaims() throws Exception {
        String docs = targetDocuments();

        assertThat(docs)
                .doesNotContain("partial legacy payload receives the full canonical contribution")
                .doesNotContain("partial legacy payloads receive full canonical contributions")
                .doesNotContain("flag-only payloads receive the full canonical")
                .doesNotContain("candidate-only payloads receive the full canonical")
                .doesNotContain("top-level window values are ignored")
                .doesNotContain("top-level windows are ignored")
                .doesNotContain("unsupported currencies use a safe fallback")
                .doesNotContain("unknown currencies use a safe fallback")
                .doesNotContain("unknown currencies are converted with rate 1")
                .doesNotContain("unsupported currencies are converted with rate 1")
                .doesNotContain("rules v2 is available")
                .doesNotContain("rules v2 exists in fdp-129")
                .doesNotContain("legacy compatibility has been removed")
                .doesNotContain("legacy flags have been removed")
                .doesNotContain("provides distributed acid")
                .doesNotContain("guarantees exactly-once kafka")
                .doesNotContain("provides payment authorization")
                .doesNotContain("performs automatic approval")
                .doesNotContain("performs automatic decline")
                .doesNotContain("performs automatic block")
                .doesNotContain("performs model promotion");
    }

    private String targetDocuments() throws Exception {
        return String.join("\n",
                        Files.readString(docsRoot().resolve("architecture/feature_snapshot_consumption_policy.md")),
                        Files.readString(docsRoot().resolve("architecture/engine_intelligence_semantic_ownership_adr.md")),
                        Files.readString(docsRoot().resolve("architecture/rule_based_signal_engine_adapter.md")),
                        Files.readString(docsRoot().resolve("architecture/fraud_scoring_orchestrator.md")),
                        Files.readString(docsRoot().resolve("configuration/configuration_guide.md")),
                        Files.readString(docsRoot().resolve("architecture/index.md")))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
