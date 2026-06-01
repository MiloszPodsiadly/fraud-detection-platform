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
                "Enabled mode may invoke diagnostic enrichment",
                "must not change baseline `fraudScore`, `riskLevel`,",
                "Enrichment failure returns the base event",
                "Baseline scoring failures are not swallowed",
                "Set `fraud.scoring.events.engine-intelligence.emit-enabled=false` and redeploy",
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
