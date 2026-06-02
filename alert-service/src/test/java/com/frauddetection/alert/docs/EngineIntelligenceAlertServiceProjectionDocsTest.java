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
                "## Non-goals",
                "## Projection-only Boundary",
                "## Storage Model",
                "## Projection Policy and Limits",
                "## Old Event Compatibility",
                "## New Bounded Event Projection",
                "## Invalid/Oversized Safe Omission",
                "## Idempotency/Replay Safety",
                "## Mongo projection identity and idempotency",
                "## No Raw/Internal Storage",
                "## No API/UI Exposure",
                "## No Decisioning",
                "## Failure Isolation Ownership",
                "## Future Operational Hardening",
                "## Future FDP-96 API Read Model",
                "FDP-95 projects bounded engine intelligence internally in alert-service.",
                "FDP-95 does not expose engine intelligence through API/UI.",
                "FDP-95 does not use engine intelligence for decisions.",
                "Old events without engineIntelligence remain compatible.",
                "Projection failure must not break base alert projection.",
                "Projection must be idempotent under replay.",
                "Engine-intelligence projection uses transactionId as Mongo `_id`.",
                "Mongo `_id` uniqueness is the idempotency boundary for FDP-95.",
                "Reprocessing the same transaction replaces the projection state instead of appending duplicate",
                "No separate migration is required for this document-style projection unless deployment",
                "Future hardening may add secondary indexes or retention/TTL",
                "Only bounded public event contract fields may be stored.",
                "Alert-service revalidates reason codes by reconstructing FDP-92 public DTOs rather than maintaining a second",
                "does not maintain a divergent second source of truth for public enum allowlists.",
                "Storage-specific limits are",
                "`EngineIntelligenceProjectionService` owns normal projection failure isolation",
                "`TransactionMonitoringService` retains last-resort containment",
                "Projection omitted/success/failure metrics are future operational hardening.",
                "Metrics must remain low-cardinality.",
                "The base projection must never depend on metrics.",
                "Raw evidence, raw contributions, feature vectors,",
                "endpoints, tokens, secrets, stack traces, exception messages, and internal aggregation objects must not be stored.",
                "API/UI exposure is future FDP-96/FDP-97 scope.",
                "Final decisioning remains out of scope."
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
