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
                "## Authorization Model",
                "## Missing Projection Behavior",
                "## Projection Read Failures",
                "## Read-Model Validation Rationale",
                "## Architecture Guard Evidence",
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
                "FDP-96 intentionally inherits the current scored-transaction global read authority: `TRANSACTION_MONITOR_READ`.",
                "Under the current model, any user with `TRANSACTION_MONITOR_READ` may read engine intelligence for an existing scored transaction ID.",
                "This is acceptable only because it matches the current scored transaction read boundary.",
                "tenant, case, ownership, or row-level authorization",
                "must inherit the same resource-level checks before projection lookup.",
                "Projection repository lookup must remain after scored transaction access/existence validation.",
                "FDP-96 does not claim that tenant, case, ownership, or row-level authorization exists today.",
                "FDP-96 returns `503` when the projection store cannot be read.",
                "corrupted, stale, or violates bounded API policy",
                "The public API intentionally does not expose raw corruption details.",
                "Corrupted projection is not treated as `NOT_PROJECTED`",
                "`PROJECTION_STORE_UNAVAILABLE`",
                "`PROJECTION_INVALID_SHAPE`",
                "transactionId, customerId, accountId, merchantId, raw exception, endpoint, payload, token, or raw corrupted value",
                "API read model does not blindly trust stored Mongo projection.",
                "FDP-96 revalidates stored projection before response mapping.",
                "This is intentional defense-in-depth against stale or corrupted documents.",
                "The validation reuses FDP-92/FDP-95 public/projection contracts to avoid a separate API allowlist source of truth.",
                "Read path remains single-transaction and does not call scoring, ML, rules, orchestrator, Kafka, or producer code.",
                "Source-scan guards remain architecture tripwires.",
                "FDP-96 relies on behavior-level controller/API serialization tests for API safety.",
                "Source scans are supplementary and can false-positive on comments or renames.",
                "Future FDP-97 UI must add behavior-level UI tests, not only source scans.",
                "Future feedback workflow must add behavior-level workflow tests.",
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
