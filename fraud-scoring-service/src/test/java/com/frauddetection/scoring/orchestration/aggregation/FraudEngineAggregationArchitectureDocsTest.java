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
                "agreement requires comparable available engine results and means exact risk-level alignment",
                "adjacent risk variance means comparable engines differ by one risk level",
                "adjacent risk variance is internal diagnostic metadata, not final decisioning",
                "material risk mismatch means comparable engines differ by more than one risk level",
                "disagreement is not final decision",
                "score delta is not calibration proof",
                "strongest signals are internal diagnostics",
                "strongest signals are strongest by bounded internal diagnostic ordering",
                "signal category, risk severity, score, engine-order tie-breaker, and reason code",
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
