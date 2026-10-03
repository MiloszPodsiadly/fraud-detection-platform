package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceAlertServiceProjectionDocsTest {

    @Test
    void docsExplainProjectionScopeAndNonGoals() throws Exception {
        assertThat(readDocs()).contains(
                "## Purpose",
                "## Scope",
                "## Current Scope",
                "## Non-goals",
                "## Projection-only Boundary",
                "## Storage Model",
                "## Projection Policy and Limits",
                "## Old Event Compatibility",
                "## New Bounded Event Projection",
                "## Invalid/Oversized Safe Omission",
                "## Idempotency/Replay Safety",
                "## Mongo projection identity and idempotency",
                "## Operational storage hardening",
                "## No Raw/Internal Storage",
                "## API/UI Boundary",
                "## No Decisioning",
                "## Failure Isolation Ownership",
                "## Operational Observability",
                "## API Read Model Gate",
                "Alert-service projects bounded engine intelligence into a Mongo read model.",
                "Bounded API and Analyst Console",
                "The projection does not use engine intelligence for decisions.",
                "Old events without engineIntelligence remain compatible.",
                "Events without `mlPredictionEvidence` create no private evidence document",
                "Projection failure must not break base alert projection.",
                "Projection must be idempotent under replay.",
                "Engine-intelligence projection uses transactionId as Mongo `_id`.",
                "Mongo `_id` uniqueness is the idempotency boundary for the public projection.",
                "Reprocessing the same transaction replaces the projection state instead of appending duplicate",
                "private evidence projection has stricter occurrence semantics",
                "insert-only persistence keyed by source",
                "conflicting replay is observable and cannot overwrite accepted",
                "Concurrent duplicate delivery produces one immutable document",
                "No separate migration is required for this document-style projection unless deployment",
                "Future hardening may add secondary indexes or retention/TTL",
                "public projection does not add query-optimized secondary indexes.",
                "It does not add TTL or retention policy.",
                "Projection growth is expected to be roughly one document per scored transaction with engineIntelligence.",
                "Before broader producer rollout, define:",
                "whether retention matches scored transactions;",
                "whether projection is cleaned up with scored transaction;",
                "public projection stores only bounded public event contract fields.",
                "dedicated internal evidence collection stores",
                "Alert-service revalidates reason codes by reconstructing public DTOs rather than maintaining a second",
                "does not maintain a divergent second source of truth for public enum allowlists.",
                "Storage-specific limits are",
                "`EngineIntelligenceProjectionService` owns normal projection failure isolation",
                "`TransactionMonitoringService` retains last-resort containment",
                "Both projection paths record low-cardinality counters and latency",
                "Export and retention remain responsibilities of the configured Micrometer backend.",
                "Metrics must never affect base projection.",
                "Raw model requests/responses, raw",
                "features, raw contributions, arbitrary metadata",
                "The exact evidence collection has no controller, public read DTO, feedback-record field, dataset-export",
                "Bounded API/UI exposure exists through later scoped Engine Intelligence work.",
                "API/UI layers consume",
                "dedicated read DTOs and validators rather than the projection class directly.",
                "Final decisioning remains out of scope."
        );
    }

    @Test
    void projectionMetricsHaveAnImplementedLowCardinalityBoundary() throws Exception {
        assertThat(readDocs()).contains(
                "`engine_intelligence_projection_attempt_total`",
                "`engine_intelligence_projection_success_total`",
                "`engine_intelligence_projection_omitted_total{reason=bounded_reason}`",
                "`engine_intelligence_projection_latency_seconds`",
                "`ml_prediction_evidence_projection_*`",
                "Allowed labels are bounded result/reason values owned by code.",
                "Forbidden labels include",
                "raw reason code if",
                "unbounded. Metrics must never affect base projection."
        );
    }

    @Test
    void apiGateRemainsAsCurrentReadModelChecklist() throws Exception {
        assertThat(readDocs()).contains(
                "The projection originally required separate review before API/UI exposure.",
                "The current bounded API, OpenAPI, and UI",
                "contracts satisfy that gate.",
                "The guard remains useful as a checklist for any future read-model",
                "change. API read-model tests must prove:",
                "API read-model tests must prove:",
                "a bounded response DTO;",
                "no raw/internal projection leakage;",
                "no final decisioning fields;",
                "old cases without projection remain compatible;",
                "authorization boundaries;",
                "no high-cardinality/raw values;",
                "timeout/unavailable/degraded status semantics remain safe."
        );
    }

    private String readDocs() throws IOException {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path docs = candidate.resolve("docs/architecture/engine_intelligence_alert_service_projection.md");
            if (Files.isRegularFile(docs)) {
                return Files.readString(docs);
            }
        }
        throw new IllegalStateException("ENGINE_INTELLIGENCE_PROJECTION_DOCS_MISSING");
    }
}
