package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceConsumerReadinessDocsTest {

    @Test
    void docsStateConsumerFirstBoundaryAndFutureProducerGate() throws Exception {
        String docs = EngineIntelligenceFdp93SourceScanSupport.read(
                "docs/architecture/engine_intelligence_consumer_readiness.md"
        );

        assertThat(docs).contains(
                "Consumer-first Engine Intelligence Rollout Readiness",
                "Do not emit what consumers have not proven they can safely tolerate",
                "FDP-93 proved consumers could safely tolerate engineIntelligence before any producer emitted it",
                "FDP-93 was consumer-readiness, not product exposure",
                "Current Engine Intelligence exposure exists through later scoped",
                "did not emit engineIntelligence in production runtime",
                "did not add alert-service projection or persist engineIntelligence",
                "did not expose engineIntelligence through API/UI",
                "does not add final decisioning",
                "Alert-service may prove deserialization readiness only",
                "producer emission remained a separate reviewed",
                "Producer emission must be disabled by default",
                "guarded by an explicit feature flag",
                "required consumer-readiness proof",
                "future projection behavior change still requires separate review",
                "Producer emission requires an explicit rollout flag",
                "Producer emission was not allowed until FDP-93 consumer-readiness tests were green",
                "Old event shape remained the default until rollout was explicitly enabled",
                "Producer tests in later branches had to cover enabled and disabled modes",
                "Producer emission must preserve FDP-92 public contract semantics",
                "Producer emission must not introduce final decisioning",
                "Producer emission must not hide projection/API/UI changes unless they are explicitly scoped and reviewed",
                "FDP-92 proves the DTO is bounded",
                "FDP-93 proves consumers tolerate the bounded DTO",
                "FDP-93 intentionally requires alert-service tolerance for unknown top-level TransactionScoredEvent",
                "fields as a forward-compatibility guardrail, not only for engineIntelligence nested fields",
                "Unknown top-level tolerance does not authorize producers to emit arbitrary fields",
                "Producer branches must still",
                "define exact public payload shape",
                "Consumer tolerance is not producer looseness",
                "Source-scan guards are intentionally strict",
                "consumer inventory review is required",
                "Source-scan guards are not a substitute for architectural review",
                "New TransactionScoredEvent",
                "consumers must be added to the inventory intentionally",
                "TRANSACTION_SCORED_EVENT_CONSUMER_INVENTORY_REVIEW_REQUIRED",
                "Fixture name prefix v1/v2 describes the TransactionScoredEvent fixture shape",
                "pre-FDP-92 scored-event shape",
                "scored-event shape with optional engineIntelligence present",
                "does not change `EngineIntelligenceSummary.contractVersion`",
                "`contractVersion = 1`",
                "FDP-93 fixtures cover valid and forward-compatible event shapes",
                "Invalid nested engineIntelligence versions remain a future producer/contract-validation hardening case",
                "unsupported engineIntelligence contract versions fail safely and boundedly",
                "Invalid-version handling must not be interpreted as consumer tolerance"
        );
    }

    @Test
    void docsDoNotContainRolloutOverclaims() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.read(
                "docs/architecture/engine_intelligence_consumer_readiness.md"
        )).doesNotContainIgnoringCase(
                "production emission enabled",
                "alert projection ready",
                "UI ready",
                "analyst console ready",
                "final decision source",
                "automatic decline",
                "automatic approve",
                "payment authorization"
        );
    }
}
