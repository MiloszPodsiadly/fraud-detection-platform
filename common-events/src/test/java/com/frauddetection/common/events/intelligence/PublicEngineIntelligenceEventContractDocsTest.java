package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PublicEngineIntelligenceEventContractDocsTest {

    @Test
    void documentsBoundedPublicContractWithoutOverclaiming() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/public_engine_intelligence_event_contract.md"))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(docs).contains(
                "safe, bounded, optional `transactionscoredevent.engineintelligence` summary",
                "current implementation wires disabled-by-default producer",
                "does not publish the internal aggregation model 1:1",
                "allowlisted projection",
                "`fraudengineaggregationresult` is internal",
                "separate public event contract",
                "versioning strategy",
                "backward compatibility rules",
                "payload limits",
                "public field allowlist",
                "score exposure decision",
                "evidence exposure decision",
                "diagnostic signal exposure decision",
                "timeout does not mean low risk",
                "missing score does not become zero",
                "missing risk does not become low",
                "`none` does not mean score zero",
                "does not mean a missing score",
                "missing score maps to `unavailable`",
                "non-available engine statuses must not carry public `risklevel`",
                "`risklevel` is omitted",
                "consumers must not infer low risk",
                "operational diagnostic signals must not carry fraud risk",
                "agreement is not approval",
                "disagreement is not decline",
                "does not expose raw `fraudengineresult`",
                "generic all-engine comparison",
                "current alert-service projection, api/ui, and controlled producer publication",
                "does not add final decisioning",
                "consumer-first rollout guard",
                "producer diagnostic enrichment remains disabled by default",
                "emission must remain explicitly controlled",
                "consumer-first rollout",
                "historical consumers may reject unknown top-level fields",
                "`transactionscoredevent.mlpredictionevidence` is a separate optional internal field",
                "every current scored event requires exactly one of it or `mlpredictionevidenceomissionreason`",
                "events with neither fail deserialization",
                "archived, or quarantined before current consumers read them",
                "matching `available` `ml.python.primary` entry",
                "matching forward-derived public score bucket",
                "never reconstructs an exact score from a bucket",
                "exact field is not part of the public engine intelligence api/ui dto",
                "top-level `fraudscore` remains the platform result",
                "producer mapping must preserve timeout does not mean low risk",
                "operational statuses do not carry `risklevel`",
                "operational diagnostic signals do not carry fraud score buckets",
                "diagnostic signals are not recommendations",
                "inventory mongo `engine_intelligence_projections` documents",
                "archive them under the approved retention policy",
                "do not synthesize identity",
                "`model_lineage_unavailable`",
                "excluded from exact-model evaluation",
                "runtime does not normalize them"
        ).doesNotContain(
                "production decisioning",
                "automatic decline",
                "authorize payments"
        );
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
