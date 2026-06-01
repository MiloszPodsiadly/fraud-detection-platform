package com.frauddetection.scoring.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceProducerEmissionRolloutDocsTest {

    @Test
    void docsStateFlagRollbackAndLiveRuntimeLimitation() throws Exception {
        String docs = Files.readString(repositoryRoot().resolve(
                "docs/architecture/engine_intelligence_producer_emission_rollout.md"
        ));

        assertThat(docs).contains(
                "FDP-94 disabled-by-default producer capability boundary only",
                "fraud.scoring.events.engine-intelligence.emit-enabled=false",
                "Missing and explicit `false`",
                "Explicit `true`",
                "omits the `engineIntelligence` JSON field",
                "FDP-94 does not migrate baseline scoring runtime to `FraudScoringOrchestrator`",
                "Runtime orchestration emission requires a separate reviewed future branch",
                "Optional enrichment failures return the base scored event",
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
