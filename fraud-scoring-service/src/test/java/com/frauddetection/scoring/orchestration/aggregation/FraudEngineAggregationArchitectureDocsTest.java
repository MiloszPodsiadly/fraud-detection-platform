package com.frauddetection.scoring.orchestration.aggregation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineAggregationArchitectureDocsTest {

    @Test
    void documentsInternalAggregationBoundary() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/internal_engine_result_aggregation.md"))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(docs).contains(
                "fdp-91",
                "internal comparison semantics",
                "before engine intelligence is published externally",
                "internal-only",
                "no `transactionscoredevent.engineresults[]`",
                "no kafka schema changes",
                "no alert-service projection",
                "no api/ui",
                "no final decisioning",
                "agreement is not proof of fraud",
                "disagreement is not final decision",
                "score delta is not calibration proof",
                "strongest signals are internal diagnostics",
                "timeout does not mean low risk",
                "missing score does not become zero",
                "missing risk does not become low risk",
                "raw evidence is not propagated",
                "high-cardinality identifiers are forbidden",
                "evidence is bounded",
                "result size is bounded"
        ).doesNotContain(
                "public engine intelligence",
                "production decision source",
                "final fraud decision",
                "approve transaction",
                "decline transaction",
                "block card",
                "payment authorization decision"
        );
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
