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
                "Disabled mode keeps the",
                "does not invoke orchestrator, aggregation, or public mapper",
                "does not initialize the conditional diagnostic runtime graph",
                "Enabled mode performs shadow",
                "diagnostic orchestration after baseline scoring",
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
                "Operational Observability Debt",
                "FDP-94 has bounded failure isolation but does not yet add dedicated enrichment metrics",
                "`enrichment_attempt_total`",
                "`enrichment_success_total`",
                "`enrichment_omitted_total`",
                "`enrichment_latency_seconds`",
                "`enrichment_timeout_total` if applicable",
                "Labels must be low-cardinality only",
                "Transaction, customer, and account IDs must not be metrics",
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
                "automatic approve enabled"
        );
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("fraud-scoring-service"))
                ? current
                : current.getParent();
    }
}
