package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceApiReadModelDocsTest {

    @Test
    void documentsBoundedTransactionScopedApiWithoutUiOrDecisioningClaims() throws Exception {
        String documentation = Files.readString(documentationPath());

        assertThat(documentation).contains(
                "## Purpose",
                "## Scope",
                "## Endpoint",
                "## Why Transaction-Scoped Endpoint First",
                "## DTO Boundary",
                "## Authorization Boundary",
                "## Missing Projection Behavior",
                "## Operational Status Semantics",
                "## Raw/Internal Leakage Prevention",
                "## No Decisioning",
                "## No UI",
                "## OpenAPI Boundary",
                "## Future FDP-97 UI",
                "## Future Case-Level Aggregation If Needed",
                "FDP-96 exposes projected engine intelligence through a bounded read-only API DTO.",
                "FDP-96 does not return EngineIntelligenceProjection directly.",
                "FDP-96 uses a dedicated transaction-scoped endpoint first because FDP-95 projection is keyed by transactionId.",
                "FDP-96 does not add list/search endpoints for engine intelligence.",
                "FDP-96 does not add case-level aggregation.",
                "Missing projection is normal for old transactions and disabled producer periods.",
                "Authorization must be checked before projection lookup.",
                "Read service must not call scoring, ML, rules, orchestrator, or Kafka.",
                "TIMEOUT/UNAVAILABLE/DEGRADED must not be represented as LOW risk.",
                "API must not expose raw/internal fields.",
                "API must not expose finalDecision/recommendedAction/approve/decline/block.",
                "UI remains FDP-97 scope."
        );
    }

    private Path documentationPath() {
        Path fromRoot = Path.of("docs", "architecture", "engine_intelligence_api_read_model.md");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("..", "docs", "architecture", "engine_intelligence_api_read_model.md");
    }
}
