package com.frauddetection.alert.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceAlertServiceProjectionDocsTest {

    @Test
    void docsExplainFdp95ProjectionScopeAndNonGoals() throws Exception {
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
                "## Future Operational Hardening",
                "## Historical FDP-96 API Read Model Gate",
                "Alert-service projects bounded engine intelligence into a Mongo read model.",
                "Historical FDP-95 introduced the",
                "The projection does not use engine intelligence for decisions.",
                "Old events without engineIntelligence remain compatible.",
                "Projection failure must not break base alert projection.",
                "Projection must be idempotent under replay.",
                "Engine-intelligence projection uses transactionId as Mongo `_id`.",
                "Mongo `_id` uniqueness is the idempotency boundary for FDP-95.",
                "Reprocessing the same transaction replaces the projection state instead of appending duplicate",
                "No separate migration is required for this document-style projection unless deployment",
                "Future hardening may add secondary indexes or retention/TTL",
                "FDP-95 does not add query-optimized secondary indexes.",
                "FDP-95 does not add TTL or retention policy.",
                "Projection growth is expected to be roughly one document per scored transaction with engineIntelligence.",
                "Before the FDP-96 API read model or broader producer rollout, define:",
                "whether retention matches scored transactions;",
                "whether projection is cleaned up with scored transaction;",
                "Only bounded public event contract fields may be stored.",
                "Alert-service revalidates reason codes by reconstructing FDP-92 public DTOs rather than maintaining a second",
                "does not maintain a divergent second source of truth for public enum allowlists.",
                "Storage-specific limits are",
                "`EngineIntelligenceProjectionService` owns normal projection failure isolation",
                "`TransactionMonitoringService` retains last-resort containment",
                "Projection metrics are future operational hardening.",
                "FDP-95 does not add production metrics backend.",
                "Metrics must never affect base projection.",
                "Raw evidence, raw contributions, feature vectors,",
                "endpoints, tokens, secrets, stack traces, exception messages, and internal aggregation objects must not be stored.",
                "Bounded API/UI exposure exists through later scoped Engine Intelligence work.",
                "API/UI layers consume",
                "dedicated read DTOs and validators rather than the projection class directly.",
                "Final decisioning remains out of scope."
        );
    }

    @Test
    void projectionMetricsAreFutureLowCardinalityHardening() throws Exception {
        assertThat(readDocs()).contains(
                "`engine_intelligence_projection_attempt_total`",
                "`engine_intelligence_projection_success_total`",
                "`engine_intelligence_projection_omitted_total{reason=bounded_reason}`",
                "`engine_intelligence_projection_latency_seconds`",
                "Allowed labels are bounded `result`, `omission_reason`, and `projection_version`.",
                "Forbidden labels include",
                "raw reason code if",
                "unbounded. Metrics must never affect base projection."
        );
    }

    @Test
    void historicalFdp96GateRemainsAsCurrentReadModelChecklist() throws Exception {
        assertThat(readDocs()).contains(
                "Historical FDP-95 required separate FDP-96/FDP-97 review before API/UI exposure.",
                "That gate has been superseded by the",
                "current bounded API, OpenAPI, and UI contracts.",
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
        throw new IllegalStateException("FDP95_DOCS_MISSING");
    }
}
