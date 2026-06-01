package com.frauddetection.scoring.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceProducerEmissionRolloutDocsTest {

    @Test
    void docsStateFlagRollbackAndLiveRuntimeBoundary() throws Exception {
        String docs = Files.readString(repositoryRoot().resolve(
                "docs/architecture/engine_intelligence_producer_emission_rollout.md"
        ));

        assertThat(docs).contains(
                "FDP-94 disabled-by-default runtime producer emission",
                "fraud.scoring.events.engine-intelligence.emit-enabled=false",
                "Missing config means disabled",
                "Explicit `false` means disabled",
                "Explicit `true` enables producer-side diagnostic enrichment",
                "omits the `engineIntelligence` JSON field",
                "Disabled mode keeps the pre-FDP-94 serialized event shape.",
                "It does not invoke orchestrator, aggregation, public mapper, rules, or ML diagnostic path.",
                "It does not initialize the conditional diagnostic runtime graph.",
                "Enabled mode performs shadow diagnostic orchestration after baseline scoring.",
                "It attaches bounded public `engineIntelligence`.",
                "Enabled mode may execute rule and ML signal engines in addition to baseline scoring",
                "Enabled mode may add latency, ML service calls, executor work, and operational load",
                "Enabled diagnostic results must not change baseline `fraudScore`, `riskLevel`, `alertRecommended`,",
                "Enabled diagnostic results may differ from the",
                "Such disagreement is diagnostic only and not final decisioning",
                "Enrichment failure returns the base event",
                "Baseline scoring failures are not swallowed",
                "Keep enabled mode disabled by default until latency and load are validated",
                "Enable emission gradually",
                "Verify latency, timeout, rejection, and enrichment-omission behavior",
                "Set `fraud.scoring.events.engine-intelligence.emit-enabled=false` and redeploy",
                "Operational Observability Boundary",
                "FDP-94 includes a no-op metrics boundary",
                "Metrics recording is best-effort and cannot block event publishing",
                "Production metrics backend integration remains future scope",
                "Before wider rollout",
                "`enrichment_attempt_total`",
                "`enrichment_success_total`",
                "`enrichment_omitted_total`",
                "`enrichment_latency_seconds`",
                "`enrichment_timeout_total` if applicable",
                "`recordSuccess` means a public `EngineIntelligenceSummary` was actually produced",
                "A completed",
                "diagnostic pipeline that returns empty is recorded as a bounded omission",
                "Enabled enrichment",
                "attempts record latency for success, empty result, missing pipeline, and failure",
                "Disabled skips do",
                "not record enrichment attempt latency",
                "FDP-94 records `UNKNOWN_FAILURE` for runtime pipeline failures",
                "Stage-specific omission reasons are",
                "reserved for future pipeline instrumentation",
                "Current omission reasons remain bounded and",
                "low-cardinality",
                "Raw exception messages are not used as omission reasons",
                "Metrics are best-effort and must not affect event publishing",
                "Metrics must remain low-cardinality",
                "Metrics must not include transaction IDs, customer IDs, account IDs, raw exception messages,",
                "endpoint URLs, payloads, or feature vectors",
                "No alert-service projection or persistence",
                "No API or analyst-console UI exposure",
                "No final decisioning",
                "No raw or internal aggregation serialization"
        );
    }

    @Test
    void docsDoNotClaimDefaultOrLiveEmission() throws Exception {
        assertThat(Files.readString(repositoryRoot().resolve(
                "docs/architecture/engine_intelligence_producer_emission_rollout.md"
        ))).doesNotContainIgnoringCase(
                "production emission enabled by default",
                "live orchestrator migration complete",
                "alert projection ready",
                "analyst console ready",
                "automatic decline enabled",
                "automatic approve enabled",
                "payment authorization enabled",
                "final decision source",
                "orchestrator replaces baseline scoring",
                "engineIntelligence changes fraudScore"
        );
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("fraud-scoring-service"))
                ? current
                : current.getParent();
    }
}
