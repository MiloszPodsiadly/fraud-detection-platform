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
                "safe, bounded, backward-compatible public engine intelligence event",
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
                "does not add alert-service projection",
                "does not add api/ui",
                "does not add final decisioning",
                "future fdp-93 projection scope"
        ).doesNotContain(
                "production decisioning",
                "analyst console ready",
                "ui ready",
                "alert projection ready",
                "automatic decline",
                "payment authorization"
        );
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
