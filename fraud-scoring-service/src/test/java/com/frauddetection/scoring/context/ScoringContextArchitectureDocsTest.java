package com.frauddetection.scoring.context;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringContextArchitectureDocsTest {

    @Test
    void documentsInternalBoundaryAndRejectedIntegrationClaims() throws Exception {
        String document = Files.readString(docsRoot().resolve("architecture/scoring_context_boundary.md"));
        String docs = document.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("scoringcontext")
                .contains("internal input boundary")
                .contains("fraud-scoring-service")
                .contains("not `common-events`")
                .contains("not a kafka event")
                .contains("not an api dto")
                .contains("not a storage document")
                .contains("not a public cross-service contract")
                .contains("not a new source of truth")
                .contains("no runtime scoring behavior change")
                .contains("metadata in v1")
                .contains("raw payloads")
                .contains("derived scores")
                .contains("engine outputs")
                .contains("final decisions")
                .contains("no final decisioning")
                .contains("no orchestrator")
                .contains("fraudscoringorchestrator")
                .contains("event extension")
                .contains("api/ui")
                .contains("isolation guard was updated")
                .contains("narrowed only for internal `scoringcontext`")
                .contains("feature snapshot semantics")
                .contains("defensively copies the top-level `featuresnapshot` map")
                .contains("null `featuresnapshot` keys are not allowed")
                .contains("null `featuresnapshot` values are not allowed")
                .contains("nested mutable values are not deep-copied")
                .contains("future engine adapters must treat `featuresnapshot` values as read-only")
                .contains("typed or bounded feature snapshot policy belongs to a later branch")
                .contains("does not compute features")
                .contains("does not enrich features")
                .contains("does not normalize features")
                .contains("does not validate the business meaning of features");

        assertThat(docs)
                .doesNotContain("automatic approve")
                .doesNotContain("automatic decline")
                .doesNotContain("transaction blocking")
                .doesNotContain("ml final decision source")
                .doesNotContain("core banking authorization")
                .doesNotContain("public api contract")
                .doesNotContain("kafka event contract")
                .doesNotContain("`scoringcontext` in `common-events`");
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
