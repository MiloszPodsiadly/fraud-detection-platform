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
                "result size is bounded",
                "compares only the allowlisted pair rules.primary and ml.python.primary",
                "not an n-way multi-engine comparison framework",
                "future engine requires a separate branch",
                "explicit semantic review",
                "unknown engine ids fail fast",
                "warning counts may be derived internally from bounded warning codes",
                "must not include raw text",
                "fraudengineaggregationresult is an internal model",
                "must not publish fraudengineaggregationresult 1:1",
                "separate compatibility-reviewed public event contract",
                "own schema",
                "backward compatibility review",
                "data safety review",
                "score may be exposed, bucketed, templated, or omitted",
                "internal aggregation types must not be placed in common-events directly",
                "bounded internal diagnostic summaries",
                "not analyst recommendations",
                "not final explanations",
                "not a payment decision rationale",
                "not a global proof of fraud",
                "future api/ui may rename or reshape this concept",
                "define separate public dto/event contract",
                "do not reuse internal aggregation record directly",
                "decide whether raw score is allowed, bucketed, or omitted",
                "decide whether evidence title/description are allowed, templated, or omitted",
                "decide whether contribution feature is allowed, categorized, or omitted",
                "use allowlist-first public schema",
                "enforce payload size limits",
                "enforce backward compatibility tests",
                "enforce raw leakage tests",
                "enforce no final decisioning",
                "preserve timeout/unavailable/degraded semantics",
                "preserve missing score does not become zero",
                "preserve missing risk does not become low",
                "preserve agreement/disagreement/adjacent variance semantics"
        ).doesNotContain(
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
